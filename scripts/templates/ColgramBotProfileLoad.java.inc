        final long selfId = getUserConfig().getClientUserId();
        TLRPC.UserFull userFull = getMessagesController().getUserFull(selfId);

        // A bot account has no usable MTProto UserFull. Ask the bot-specific
        // MTProto method for its live profile, through the same route as chats.
        final TLRPC.User colgramBotSelf = getUserConfig().getCurrentUser();
        if (colgramBotSelf != null && colgramBotSelf.bot && userFull == null) {
            valueSet = true;
            firstNameEdit.setText(currentFirstName = colgramBotSelf.first_name);
            lastNameEdit.setText(currentLastName = colgramBotSelf.last_name);
            checkDone(true);
            TL_bots.getBotInfo req = new TL_bots.getBotInfo();
            req.lang_code = "";
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TL_bots.TL_botInfoLocalized) {
                    TL_bots.TL_botInfoLocalized info = (TL_bots.TL_botInfoLocalized) response;
                    if (info.name != null && !info.name.isEmpty()) {
                        firstNameEdit.setText(currentFirstName = info.name);
                        lastNameEdit.setText(currentLastName = "");
                    }
                    bioEdit.setText(currentBio = info.description == null ? "" : info.description);
                    checkDone(true);
                }
            }));
            return;
        }

        if (userFull == null) {
            getMessagesController().loadUserInfo(getUserConfig().getCurrentUser(), true, getClassGuid());
            return;
        }
