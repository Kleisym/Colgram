        if (user != null && user.bot) {
            // bots.setBotInfo works for the current bot without an InputUser.
            // Unlike api.telegram.org, it follows the app's working MTProto route.
            final String botFirst = firstNameEdit.getText().toString();
            final String botLast = lastNameEdit.getText().toString();
            final String botBio = bioEdit.getText().toString();
            final boolean nameChanged = !TextUtils.equals(currentFirstName, botFirst)
                    || !TextUtils.equals(currentLastName, botLast);
            final boolean bioChanged = !TextUtils.equals(currentBio, botBio);
            TL_bots.setBotInfo req = new TL_bots.setBotInfo();
            req.lang_code = "";
            if (nameChanged) {
                req.name = (botFirst + " " + botLast).trim();
                req.flags |= 8;
            }
            if (bioChanged) {
                int points = Math.min(120, botBio.codePointCount(0, botBio.length()));
                req.about = botBio.substring(0, botBio.offsetByCodePoints(0, points));
                req.description = botBio;
                req.flags |= 1 | 2;
            }
            if (req.flags == 0) {
                finishFragment();
                return;
            }
            getConnectionsManager().sendRequest(req, (response, rpcError) -> AndroidUtilities.runOnUIThread(() -> {
                if (rpcError != null || !(response instanceof TLRPC.TL_boolTrue)) {
                    doneButtonDrawable.animateToProgress(0f);
                    android.widget.Toast.makeText(
                            org.telegram.messenger.ApplicationLoader.applicationContext,
                            "Профиль бота: " + (rpcError != null ? rpcError.text : "нет подтверждения сервера"),
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                user.first_name = botFirst;
                user.last_name = botLast;
                getUserConfig().saveConfig(true);
                if (userFull != null) {
                    userFull.about = botBio;
                    userFull.flags = TextUtils.isEmpty(botBio)
                            ? (userFull.flags & ~2) : (userFull.flags | 2);
                    getMessagesStorage().updateUserInfo(userFull, false);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(
                            NotificationCenter.userInfoDidLoad, user.id, userFull);
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.mainUserInfoChanged);
                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                finishFragment();
            }));
            return;
        }
        if (user == null || userFull == null) {
            doneButtonDrawable.animateToProgress(0f);
            return;
        }
