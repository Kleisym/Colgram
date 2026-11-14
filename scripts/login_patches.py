"""Pinned, idempotent phone-form repairs, separate from onboarding artwork."""


def patch_phone_focus(source):
    marker = '// COLGRAM_PHONE_FOCUS = 1'
    if marker in source:
        return source
    anchor = '''                        if (textToSet != null) {
                            phoneField.requestFocus();
                            phoneField.setText(textToSet);
                            phoneField.setSelection(phoneField.length());
                        }'''
    if anchor not in source:
        raise ValueError('Missing pinned PhoneView country-code completion block')
    # A complete calling code may belong to several countries or prefix a regional
    # code (+7 Russia/Kazakhstan, +1 NANP). That does not make it incomplete.
    # Preserve the national number and reuse the upstream focus/formatting path.
    replacement = '''                        // COLGRAM_PHONE_FOCUS = 1
                        if (textToSet == null && country != null && codesMap.containsKey(text)
                                && codeField.hasFocus() && !text.isEmpty()) {
                            textToSet = phoneField.getText().toString();
                        }
''' + anchor
    return source.replace(anchor, replacement, 1)


def patch_phone_form(source):
    marker = '// COLGRAM_PHONE_SYSTEM_IME'
    if marker in source:
        return source
    start = source.index('    public class PhoneView extends SlideView ')
    end = source.index('        private void requestPasskey(boolean clickedButton)', start)
    phone = source[start:end]

    def replace(anchor, body):
        nonlocal phone
        if phone.count(anchor) != 1:
            raise ValueError('Missing or ambiguous pinned PhoneView anchor: ' + anchor.splitlines()[0])
        phone = phone.replace(anchor, body, 1)

    replace('''        public boolean hasCustomKeyboard() {
            return true;
        }''', '''        public boolean hasCustomKeyboard() {
            return false; // COLGRAM_PHONE_SYSTEM_IME: do not reserve a fixed 230dp below the form
        }''')
    replace('''            phoneField.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);''',
            '''            phoneField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);''')
    replace('''                if (i == EditorInfo.IME_ACTION_NEXT) {
                    if (phoneNumberConfirmView != null) {
                        phoneNumberConfirmView.fabButton.callOnClick();
                        return true;
                    }
                    onNextPressed(null);
                    return true;
                }''', '''                if (i == EditorInfo.IME_ACTION_DONE || i == EditorInfo.IME_ACTION_NEXT
                        || keyEvent != null && keyEvent.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                            && keyEvent.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (phoneNumberConfirmView == null) onNextPressed(null);
                    return true;
                }''')
    replace('''        @Override
        public void onCancelPressed() {
            nextPressed = false;
        }''', '''        private int colgramPhoneAttempt;
        private int colgramPhoneRequestId;
        private Runnable colgramPhoneTimeout;

        private void clearColgramPhoneTimeout() {
            if (colgramPhoneTimeout != null) {
                AndroidUtilities.cancelRunOnUIThread(colgramPhoneTimeout);
                colgramPhoneTimeout = null;
            }
        }

        private void cancelColgramPhoneRequest() {
            colgramPhoneAttempt++;
            clearColgramPhoneTimeout();
            final int request = colgramPhoneRequestId;
            colgramPhoneRequestId = 0;
            if (request != 0) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(request, true);
                if (progressRequestId == request) progressRequestId = 0;
            }
        }

        private void showColgramPhoneNetworkError() {
            if (getParentActivity() == null) return;
            final boolean ru = LocaleController.getInstance().getCurrentLocaleInfo() != null
                    && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(ru ? "Нет ответа от Telegram" : "No reply from Telegram");
            builder.setMessage(ru
                    ? "Не удалось запросить код по текущему соединению. Проверьте сеть или смените режим подключения."
                    : "Could not request a code on the current connection. Check your network or change the connection mode.");
            builder.setPositiveButton(getString(R.string.OK), null);
            builder.setNeutralButton(ru ? "Подключение" : "Connection", (dialog, which) -> {
                if (proxyButtonView != null) proxyButtonView.performClick();
            });
            showDialog(builder.create());
        }

        @Override
        public void onCancelPressed() {
            cancelColgramPhoneRequest();
            nextPressed = false;
        }''')
    replace('''            phoneInputData.patterns = phoneFormatMap.get(codeField.getText().toString());
            int reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                nextPressed = false;''', '''            phoneInputData.patterns = phoneFormatMap.get(codeField.getText().toString());
            final int attempt = ++colgramPhoneAttempt;
            int reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (attempt != colgramPhoneAttempt) return;
                clearColgramPhoneTimeout();
                colgramPhoneRequestId = 0;
                nextPressed = false;
                if (error != null && (error.code == -1000 || TextUtils.isEmpty(error.text))) {
                    needHideProgress(false);
                    showColgramPhoneNetworkError();
                    return;
                }''')
    # Remove the previous transformer from already-patched checkouts. Pristine
    # source does not contain it; neither build silently changes the route.
    legacy_start = '''                    if (error != null && (error.code == -1000 || error.text == null)) {'''
    if legacy_start in phone:
        begin = phone.index(legacy_start)
        finish = phone.index('                    if (error.text != null) {', begin)
        phone = phone[:begin] + phone[finish:]
    replace('''            needShowProgress(reqId);
        }

        private boolean numberFilled;''', '''            colgramPhoneRequestId = reqId;
            colgramPhoneTimeout = () -> {
                if (attempt != colgramPhoneAttempt || colgramPhoneRequestId != reqId) return;
                cancelColgramPhoneRequest();
                nextPressed = false;
                needHideProgress(false);
                showColgramPhoneNetworkError();
            };
            AndroidUtilities.runOnUIThread(colgramPhoneTimeout, 30_000);
            needShowProgress(reqId);
        }

        private boolean numberFilled;''')
    replace('''        public void onDestroyActivity() {
            super.onDestroyActivity();
            if (cancelRequestingPasskey != null) {''', '''        public void onDestroyActivity() {
            cancelColgramPhoneRequest();
            super.onDestroyActivity();
            if (cancelRequestingPasskey != null) {''')
    return source[:start] + phone + source[end:]


def patch_dialogs_wordmark(source):
    marker = 'actionBar.setTitle("Colgram", statusDrawable);'
    if marker in source:
        return source
    start = source.index('                logoDrawable = context.getResources().getDrawable(R.drawable.telegram_logo_2)')
    end = source.index('                actionBar.setTitle(ssb, statusDrawable);', start)
    end += len('                actionBar.setTitle(ssb, statusDrawable);')
    return source[:start] + '                ' + marker + source[end:]
