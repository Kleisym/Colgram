# -*- coding: utf-8 -*-
"""
Colgram Workstream C — Unified "Прочитано" (Read Receipts / Checkmarks / Ghost Read) Symbiosis.

Implements 3 pillars across Telegram-Src idempotently (2-pass safe on fresh & patched trees):
  1. Single Shared Read-Receipts Engine & UI Symbiosis (ColgramReadReceiptsHelper)
  2. One Outbox Read Source of Truth (MessagesController.isOutboxMessageRead)
  3. Monotonic Persistent Ghost-Read Watermark (ColgramLocalReadStore + firewall hygiene)
"""

import os


def _eol_replace(path: str, old: str, new: str, label: str, eol_patch_fn, marker: str = "") -> None:
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
            norm = fh.read().replace("\r\n", "\n")
        if (marker and marker in norm) or new in norm:
            return
    eol_patch_fn(path, old, new, label)


def _upgrade_or_patch(
    path: str,
    legacy_variants,
    raw_old: str,
    new: str,
    label: str,
    eol_patch_fn,
    marker: str = "",
) -> None:
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
            norm = fh.read().replace("\r\n", "\n")
        if (marker and marker in norm) or new in norm:
            return
        for legacy in legacy_variants:
            if legacy and legacy in norm:
                eol_patch_fn(path, legacy, new, label)
                return
    eol_patch_fn(path, raw_old, new, label)


def _patch_pillar1_read_receipts(repo_path: str, eol_patch_fn) -> None:
    ui = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")
    comp = os.path.join(ui, "Components")

    chat_act = os.path.join(ui, "ChatActivity.java")
    photo_viewer = os.path.join(ui, "PhotoViewer.java")
    todo_menu = os.path.join(ui, "TodoItemMenu.java")
    poll_menu = os.path.join(ui, "PollItemMenu.java")
    msg_seen_view = os.path.join(ui, "MessageSeenView.java")
    reacted_header = os.path.join(comp, "ReactedHeaderView.java")
    priv_seen_view = os.path.join(comp, "MessagePrivateSeenView.java")

    # 1. ChatActivity single-tap popup eligibility (group + DM -> ColgramReadReceiptsHelper)
    _upgrade_or_patch(
        chat_act,
        (
            (
                "            final boolean colgramCircleOrVoice = message.isRoundVideo() || message.isVoice();\n"
                "            final boolean showMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && !isInScheduleMode() && currentChat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && (!message.isContentUnread() || colgramCircleOrVoice) && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().chatReadMarkExpirePeriod) && (ChatObject.isMegagroup(currentChat) || !ChatObject.isChannel(currentChat)) && (chatInfo == null || chatInfo.participants_count <= getMessagesController().chatReadMarkSizeThreshold) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) && chatMode != MODE_SAVED && message.canSetReaction() && !ChatObject.isMonoForum(currentChat);\n"
                "            final boolean showMessageAuthor = !suggestEdit && !isEphemeral && currentChat != null && !message.isOut() && ChatObject.isMonoForum(currentChat) && ChatObject.canManageMonoForum(currentAccount, currentChat) && -currentChat.linked_monoforum_id == message.getFromChatId();\n"
                "            final boolean showPrivateMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && currentChat == null && currentEncryptedChat == null && (currentUser != null && !UserObject.isUserSelf(currentUser) && !UserObject.isReplyUser(currentUser) && !UserObject.isAnonymous(currentUser) && !currentUser.bot && !UserObject.isService(currentUser.id)) && (userInfo == null || !userInfo.read_dates_private) && !isInScheduleMode() && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && (!message.isContentUnread() || colgramCircleOrVoice) && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().pmReadDateExpirePeriod) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);"
            ),
            (
                "            final boolean showMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && !isInScheduleMode() && currentChat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().chatReadMarkExpirePeriod) && (ChatObject.isMegagroup(currentChat) || !ChatObject.isChannel(currentChat)) && (chatInfo == null || chatInfo.participants_count <= getMessagesController().chatReadMarkSizeThreshold) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) && chatMode != MODE_SAVED && message.canSetReaction() && !ChatObject.isMonoForum(currentChat);\n"
                "            final boolean showMessageAuthor = !suggestEdit && !isEphemeral && currentChat != null && !message.isOut() && ChatObject.isMonoForum(currentChat) && ChatObject.canManageMonoForum(currentAccount, currentChat) && -currentChat.linked_monoforum_id == message.getFromChatId();\n"
                "            final boolean showPrivateMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && currentChat == null && currentEncryptedChat == null && (currentUser != null && !UserObject.isUserSelf(currentUser) && !UserObject.isReplyUser(currentUser) && !UserObject.isAnonymous(currentUser) && !currentUser.bot && !UserObject.isService(currentUser.id)) && (userInfo == null || !userInfo.read_dates_private) && !isInScheduleMode() && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().pmReadDateExpirePeriod) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);"
            ),
        ),
        (
            "            final boolean showMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && !isInScheduleMode() && currentChat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().chatReadMarkExpirePeriod) && (ChatObject.isMegagroup(currentChat) || !ChatObject.isChannel(currentChat)) && (chatInfo == null || chatInfo.participants_count <= getMessagesController().chatReadMarkSizeThreshold) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) && chatMode != MODE_SAVED && message.canSetReaction() && !ChatObject.isMonoForum(currentChat);\n"
            "            final boolean showMessageAuthor = !suggestEdit && !isEphemeral && currentChat != null && !message.isOut() && ChatObject.isMonoForum(currentChat) && ChatObject.canManageMonoForum(currentAccount, currentChat) && -currentChat.linked_monoforum_id == message.getFromChatId();\n"
            "            final boolean showPrivateMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && currentChat == null && currentEncryptedChat == null && (currentUser != null && !UserObject.isUserSelf(currentUser) && !UserObject.isReplyUser(currentUser) && !UserObject.isAnonymous(currentUser) && !currentUser.bot && !UserObject.isService(currentUser.id)) && (userInfo == null || !userInfo.read_dates_private) && !isInScheduleMode() && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().pmReadDateExpirePeriod) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);"
        ),
        (
            "            final boolean showMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && currentChat != null && ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, message, currentChat, chatInfo, null, null, isInScheduleMode(), chatMode);\n"
            "            final boolean showMessageAuthor = !suggestEdit && !isEphemeral && currentChat != null && !message.isOut() && ChatObject.isMonoForum(currentChat) && ChatObject.canManageMonoForum(currentAccount, currentChat) && -currentChat.linked_monoforum_id == message.getFromChatId();\n"
            "            final boolean showPrivateMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && currentChat == null && currentEncryptedChat == null && ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, message, null, null, currentUser, userInfo, isInScheduleMode(), chatMode);"
        ),
        "ChatActivity.java unify single-tap read receipts eligibility",
        eol_patch_fn,
        marker="ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, message, currentChat, chatInfo, null, null, isInScheduleMode(), chatMode)",
    )

    # 2. ChatActivity ActionMode seenByItem -> ColgramReadReceiptsHelper
    _eol_replace(
        chat_act,
        "                    boolean showSeenBtn = ColgramMessageSeenSheet.canCheckSeen(currentAccount, singleSel, currentChat, currentUser);",
        "                    boolean showSeenBtn = ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, singleSel, currentChat, chatInfo, currentUser, userInfo, isInScheduleMode(), chatMode);",
        "ChatActivity.java unify ActionMode seenByItem eligibility",
        eol_patch_fn,
        marker="ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, singleSel, currentChat, chatInfo, currentUser, userInfo",
    )

    # 3. PhotoViewer menu seenBy -> ColgramReadReceiptsHelper
    _eol_replace(
        photo_viewer,
        "                if (currentMessageObject != null && ColgramMessageSeenSheet.canCheckSeenForMessage(currentAccount, currentMessageObject)) {",
        "                if (currentMessageObject != null && ColgramReadReceiptsHelper.canShowReadReceiptsForMessage(currentAccount, currentMessageObject)) {",
        "PhotoViewer.java unify seenBy menu visibility",
        eol_patch_fn,
        marker="ColgramReadReceiptsHelper.canShowReadReceiptsForMessage(currentAccount, currentMessageObject)",
    )

    # 4 & 5. TodoItemMenu & PollItemMenu -> ColgramReadReceiptsHelper
    _item_menu_old = (
        "        final boolean showMessageSeen = !isReactionsViewAvailable && !chatActivity.isInScheduleMode() && chatActivity.currentChat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(chatActivity.getCurrentAccount()).getCurrentTime() - message.messageOwner.date < chatActivity.getMessagesController().chatReadMarkExpirePeriod) && (ChatObject.isMegagroup(chatActivity.currentChat) || !ChatObject.isChannel(chatActivity.currentChat)) && chatActivity.chatInfo != null && chatActivity.chatInfo.participants_count <= chatActivity.getMessagesController().chatReadMarkSizeThreshold && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) && chatActivity.getChatMode() != ChatActivity.MODE_SAVED && message.canSetReaction() && !ChatObject.isMonoForum(chatActivity.currentChat);\n"
        "        final boolean showMessageAuthor = chatActivity.currentChat != null && !message.isOut() && ChatObject.isMonoForum(chatActivity.currentChat) && ChatObject.canManageMonoForum(chatActivity.getCurrentAccount(), chatActivity.currentChat) && -chatActivity.currentChat.linked_monoforum_id == message.getFromChatId();\n"
        "        final boolean showPrivateMessageSeen = !isReactionsViewAvailable && chatActivity.currentChat == null && chatActivity.currentEncryptedChat == null && (chatActivity.currentUser != null && !UserObject.isUserSelf(chatActivity.currentUser) && !UserObject.isReplyUser(chatActivity.currentUser) && !UserObject.isAnonymous(chatActivity.currentUser) && !chatActivity.currentUser.bot && !UserObject.isService(chatActivity.currentUser.id)) && (chatActivity.userInfo == null || !chatActivity.userInfo.read_dates_private) && !chatActivity.isInScheduleMode() && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (chatActivity.getConnectionsManager().getCurrentTime() - message.messageOwner.date < chatActivity.getMessagesController().pmReadDateExpirePeriod) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);"
    )
    _item_menu_new = (
        "        final boolean showMessageSeen = !isReactionsViewAvailable && chatActivity.currentChat != null && ColgramReadReceiptsHelper.canShowReadReceipts(chatActivity.getCurrentAccount(), message, chatActivity.currentChat, chatActivity.chatInfo, null, null, chatActivity.isInScheduleMode(), chatActivity.getChatMode());\n"
        "        final boolean showMessageAuthor = chatActivity.currentChat != null && !message.isOut() && ChatObject.isMonoForum(chatActivity.currentChat) && ChatObject.canManageMonoForum(chatActivity.getCurrentAccount(), chatActivity.currentChat) && -chatActivity.currentChat.linked_monoforum_id == message.getFromChatId();\n"
        "        final boolean showPrivateMessageSeen = !isReactionsViewAvailable && chatActivity.currentChat == null && chatActivity.currentEncryptedChat == null && ColgramReadReceiptsHelper.canShowReadReceipts(chatActivity.getCurrentAccount(), message, null, null, chatActivity.currentUser, chatActivity.userInfo, chatActivity.isInScheduleMode(), chatActivity.getChatMode());"
    )
    _eol_replace(
        todo_menu,
        _item_menu_old,
        _item_menu_new,
        "TodoItemMenu.java unify read receipts eligibility",
        eol_patch_fn,
        marker="ColgramReadReceiptsHelper.canShowReadReceipts(chatActivity.getCurrentAccount(), message, chatActivity.currentChat",
    )
    _eol_replace(
        poll_menu,
        _item_menu_old,
        _item_menu_new,
        "PollItemMenu.java unify read receipts eligibility",
        eol_patch_fn,
        marker="ColgramReadReceiptsHelper.canShowReadReceipts(chatActivity.getCurrentAccount(), message, chatActivity.currentChat",
    )

    # 6. MessageSeenView.java — use ColgramReadReceiptsHelper (fixes if (true || user == null) & null users bugs)
    _eol_replace(
        msg_seen_view,
        (
            "        long fromId = 0;\n"
            "        if (messageObject.messageOwner.from_id != null) {\n"
            "            fromId = messageObject.messageOwner.from_id.user_id;\n"
            "        }\n"
            "        long finalFromId = fromId;\n"
            "        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "            if (error == null && response instanceof Vector) {\n"
            "                Vector vector = (Vector) response;\n"
            "                ArrayList<Long> unknownUsers = new ArrayList<>();\n"
            "                ArrayList<Long> unknownChats = new ArrayList<>();\n"
            "                HashMap<Long, TLObject> usersLocal = new HashMap<>();\n"
            "                ArrayList<Pair<Long, Integer>> allPeers = new ArrayList<>();\n"
            "                for (int i = 0, n = vector.objects.size(); i < n; i++) {\n"
            "                    Object object = vector.objects.get(i);\n"
            "                    if (object instanceof TLRPC.TL_readParticipantDate) {\n"
            "                        int date = ((TLRPC.TL_readParticipantDate) object).date;\n"
            "                        Long peerId = ((TLRPC.TL_readParticipantDate) object).user_id;\n"
            "                        if (finalFromId == peerId) {\n"
            "                            continue;\n"
            "                        }\n"
            "                        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(peerId);\n"
            "                        allPeers.add(new Pair<>(peerId, date));\n"
            "                        if (true || user == null) {\n"
            "                            unknownUsers.add(peerId);\n"
            "                        } else {\n"
            "                            usersLocal.put(peerId, user);\n"
            "                        }\n"
            "                    } else if (object instanceof Long) {\n"
            "                        Long peerId = (Long) object;\n"
            "                        if (finalFromId == peerId) {\n"
            "                            continue;\n"
            "                        }\n"
            "                        if (peerId > 0) {\n"
            "                            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(peerId);\n"
            "                            allPeers.add(new Pair<>(peerId, 0));\n"
            "                            if (true || user == null) {\n"
            "                                unknownUsers.add(peerId);\n"
            "                            } else {\n"
            "                                usersLocal.put(peerId, user);\n"
            "                            }\n"
            "                        } else {\n"
            "                            TLRPC.Chat chat1 = MessagesController.getInstance(currentAccount).getChat(-peerId);\n"
            "                            allPeers.add(new Pair<>(peerId, 0));\n"
            "                            if (true || chat1 == null) {\n"
            "                                unknownChats.add(peerId);\n"
            "                            } else {\n"
            "                                usersLocal.put(peerId, chat1);\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                }\n\n"
            "                if (unknownUsers.isEmpty()) {\n"
            "                    for (int i = 0; i < allPeers.size(); i++) {\n"
            "                        Pair<Long, Integer> pair = allPeers.get(i);\n"
            "                        peerIds.add(pair.first);\n"
            "                        dates.add(pair.second);\n"
            "                        users.add(usersLocal.get(pair.first));\n"
            "                    }\n"
            "                    updateView();\n"
            "                } else {\n"
            "                    if (ChatObject.isChannel(chat)) {\n"
            "                        TLRPC.TL_channels_getParticipants usersReq = new TLRPC.TL_channels_getParticipants();\n"
            "                        usersReq.limit = MessagesController.getInstance(currentAccount).chatReadMarkSizeThreshold;\n"
            "                        usersReq.offset = 0;\n"
            "                        usersReq.filter = new TLRPC.TL_channelParticipantsRecent();\n"
            "                        usersReq.channel = MessagesController.getInstance(currentAccount).getInputChannel(chat.id);\n"
            "                        ConnectionsManager.getInstance(currentAccount).sendRequest(usersReq, (response1, error1) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "                            if (response1 != null) {\n"
            "                                TLRPC.TL_channels_channelParticipants users = (TLRPC.TL_channels_channelParticipants) response1;\n"
            "                                for (int i = 0; i < users.users.size(); i++) {\n"
            "                                    TLRPC.User user = users.users.get(i);\n"
            "                                    MessagesController.getInstance(currentAccount).putUser(user, false);\n"
            "                                    usersLocal.put(user.id, user);\n"
            "                                }\n"
            "                                for (int i = 0; i < allPeers.size(); i++) {\n"
            "                                    Pair<Long, Integer> pair = allPeers.get(i);\n"
            "                                    peerIds.add(pair.first);\n"
            "                                    dates.add(pair.second);\n"
            "                                    this.users.add(usersLocal.get(pair.first));\n"
            "                                }\n"
            "                            }\n"
            "                            updateView();\n"
            "                        }));\n"
            "                    } else {\n"
            "                        TLRPC.TL_messages_getFullChat usersReq = new TLRPC.TL_messages_getFullChat();\n"
            "                        usersReq.chat_id = chat.id;\n"
            "                        ConnectionsManager.getInstance(currentAccount).sendRequest(usersReq, (response1, error1) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "                            if (response1 != null) {\n"
            "                                TLRPC.TL_messages_chatFull chatFull = (TLRPC.TL_messages_chatFull) response1;\n"
            "                                for (int i = 0; i < chatFull.users.size(); i++) {\n"
            "                                    TLRPC.User user = chatFull.users.get(i);\n"
            "                                    MessagesController.getInstance(currentAccount).putUser(user, false);\n"
            "                                    usersLocal.put(user.id, user);\n"
            "                                }\n"
            "                                for (int i = 0; i < allPeers.size(); i++) {\n"
            "                                    Pair<Long, Integer> pair = allPeers.get(i);\n"
            "                                    peerIds.add(pair.first);\n"
            "                                    dates.add(pair.second);\n"
            "                                    this.users.add(usersLocal.get(pair.first));\n"
            "                                }\n"
            "                            }\n"
            "                            updateView();\n"
            "                        }));\n"
            "                    }\n"
            "                }\n"
            "            } else {\n"
            "                updateView();\n"
            "            }\n"
            "        }));"
        ),
        (
            "        ColgramReadReceiptsHelper.loadGroupReadParticipants(currentAccount, messageObject, chat, false, res -> {\n"
            "            peerIds.clear();\n"
            "            dates.clear();\n"
            "            users.clear();\n"
            "            if (res != null && res.errorText == null) {\n"
            "                for (int i = 0; i < res.viewers.size(); i++) {\n"
            "                    ColgramReadReceiptsHelper.ViewerEntry e = res.viewers.get(i);\n"
            "                    peerIds.add(e.peerId);\n"
            "                    dates.add(e.date);\n"
            "                    users.add(e.obj);\n"
            "                }\n"
            "            }\n"
            "            updateView();\n"
            "        });"
        ),
        "MessageSeenView.java delegate to ColgramReadReceiptsHelper shared engine",
        eol_patch_fn,
        marker="ColgramReadReceiptsHelper.loadGroupReadParticipants(currentAccount, messageObject, chat, false, res ->",
    )

    # 7. ReactedHeaderView.java — unify gate + use shared ColgramReadReceiptsHelper (fixes silent drops & expiry mismatch)
    _upgrade_or_patch(
        reacted_header,
        (
            (
                "            boolean showSeen = chat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && (!message.isContentUnread() || (message.isRoundVideo() || message.isVoice())) && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < MessagesController.getInstance(currentAccount).chatReadMarkExpirePeriod) && (ChatObject.isMegagroup(chat) || !ChatObject.isChannel(chat)) && (chatInfo == null || chatInfo.participants_count <= MessagesController.getInstance(currentAccount).chatReadMarkSizeThreshold) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);"
            ),
        ),
        "            boolean showSeen = chat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < 7 * 86400)  && (ChatObject.isMegagroup(chat) || !ChatObject.isChannel(chat)) && chatInfo != null && chatInfo.participants_count <= MessagesController.getInstance(currentAccount).chatReadMarkSizeThreshold && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);",
        "            boolean showSeen = chat != null && org.telegram.ui.ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, message, chat, chatInfo, null, null, message != null && message.scheduled, 0);",
        "ReactedHeaderView.java unify eligibility via ColgramReadReceiptsHelper",
        eol_patch_fn,
        marker="org.telegram.ui.ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, message, chat, chatInfo",
    )
    _eol_replace(
        reacted_header,
        (
            "            if (showSeen) {\n"
            "                TLRPC.TL_messages_getMessageReadParticipants req = new TLRPC.TL_messages_getMessageReadParticipants();\n"
            "                req.msg_id = message.getId();\n"
            "                req.peer = MessagesController.getInstance(currentAccount).getInputPeer(message.getDialogId());\n"
            "                long fromId = message.messageOwner.from_id != null ? message.messageOwner.from_id.user_id : 0;\n"
            "                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {\n"
            "                    if (response instanceof Vector) {\n"
            "                        List<Long> usersToRequest = new ArrayList<>();\n"
            "                        List<Integer> dates = new ArrayList<>();\n"
            "                        Vector v = (Vector) response;\n"
            "                        for (Object obj : v.objects) {\n"
            "                            if (obj instanceof Long) {\n"
            "                                long l = (long) obj;\n"
            "                                if (fromId != l) {\n"
            "                                    usersToRequest.add(l);\n"
            "                                    dates.add(0);\n"
            "                                }\n"
            "                            } else if (obj instanceof TLRPC.TL_readParticipantDate) {\n"
            "                                long userId = ((TLRPC.TL_readParticipantDate) obj).user_id;\n"
            "                                int date = ((TLRPC.TL_readParticipantDate) obj).date;\n"
            "                                if (fromId != userId) {\n"
            "                                    usersToRequest.add(userId);\n"
            "                                    dates.add(date);\n"
            "                                }\n"
            "                            }\n"
            "                        }\n"
            "                        usersToRequest.add(fromId);\n"
            "                        dates.add(0);\n\n"
            "                        List<UserSeen> usersRes = new ArrayList<>();\n"
            "                        Runnable callback = () -> {\n"
            "                            seenUsers.addAll(usersRes);\n"
            "                            for (UserSeen p : usersRes) {\n"
            "                                boolean hasSame = false;\n"
            "                                for (int i = 0; i < users.size(); i++) {\n"
            "                                    if (MessageObject.getObjectPeerId(users.get(i).user) == MessageObject.getObjectPeerId(p.user)) {\n"
            "                                        hasSame = true;\n"
            "                                        if (p.date > 0) {\n"
            "                                            users.get(i).date = p.date;\n"
            "                                        }\n"
            "                                        break;\n"
            "                                    }\n"
            "                                }\n"
            "                                if (!hasSame) {\n"
            "                                    users.add(p);\n"
            "                                }\n"
            "                            }\n"
            "                            if (seenCallback != null)\n"
            "                                seenCallback.accept(usersRes);\n"
            "                            loadReactions();\n"
            "                        };\n"
            "                        if (ChatObject.isChannel(chat)) {\n"
            "                            TLRPC.TL_channels_getParticipants usersReq = new TLRPC.TL_channels_getParticipants();\n"
            "                            usersReq.limit = MessagesController.getInstance(currentAccount).chatReadMarkSizeThreshold;\n"
            "                            usersReq.offset = 0;\n"
            "                            usersReq.filter = new TLRPC.TL_channelParticipantsRecent();\n"
            "                            usersReq.channel = MessagesController.getInstance(currentAccount).getInputChannel(chat.id);\n"
            "                            ConnectionsManager.getInstance(currentAccount).sendRequest(usersReq, (response1, error1) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "                                if (response1 != null) {\n"
            "                                    TLRPC.TL_channels_channelParticipants users = (TLRPC.TL_channels_channelParticipants) response1;\n"
            "                                    for (int i = 0; i < users.users.size(); i++) {\n"
            "                                        TLRPC.User user = users.users.get(i);\n"
            "                                        MessagesController.getInstance(currentAccount).putUser(user, false);\n"
            "                                        int index = usersToRequest.indexOf(user.id);\n"
            "                                        if (!user.self && index >= 0) {\n"
            "                                            usersRes.add(new UserSeen(user, dates.get(index)));\n"
            "                                        }\n"
            "                                    }\n"
            "                                }\n"
            "                                callback.run();\n"
            "                            }));\n"
            "                        } else {\n"
            "                            TLRPC.TL_messages_getFullChat usersReq = new TLRPC.TL_messages_getFullChat();\n"
            "                            usersReq.chat_id = chat.id;\n"
            "                            ConnectionsManager.getInstance(currentAccount).sendRequest(usersReq, (response1, error1) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "                                if (response1 != null) {\n"
            "                                    TLRPC.TL_messages_chatFull chatFull = (TLRPC.TL_messages_chatFull) response1;\n"
            "                                    for (int i = 0; i < chatFull.users.size(); i++) {\n"
            "                                        TLRPC.User user = chatFull.users.get(i);\n"
            "                                        MessagesController.getInstance(currentAccount).putUser(user, false);\n"
            "                                        int index = usersToRequest.indexOf(user.id);\n"
            "                                        if (!user.self && index >= 0) {\n"
            "                                            usersRes.add(new UserSeen(user, dates.get(index)));\n"
            "                                        }\n"
            "                                    }\n"
            "                                }\n"
            "                                callback.run();\n"
            "                            }));\n"
            "                        }\n"
            "                    }\n"
            "                }, ConnectionsManager.RequestFlagInvokeAfter);\n"
            "            } else loadReactions();"
        ),
        (
            "            if (showSeen) {\n"
            "                org.telegram.ui.ColgramReadReceiptsHelper.loadGroupReadParticipants(currentAccount, message, chat, false, res -> {\n"
            "                    List<UserSeen> usersRes = new ArrayList<>();\n"
            "                    if (res != null && res.errorText == null) {\n"
            "                        for (int i = 0; i < res.viewers.size(); i++) {\n"
            "                            org.telegram.ui.ColgramReadReceiptsHelper.ViewerEntry entry = res.viewers.get(i);\n"
            "                            if (entry.obj != null) {\n"
            "                                usersRes.add(new UserSeen(entry.obj, entry.date));\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                    seenUsers.addAll(usersRes);\n"
            "                    for (UserSeen p : usersRes) {\n"
            "                        boolean hasSame = false;\n"
            "                        for (int i = 0; i < users.size(); i++) {\n"
            "                            if (MessageObject.getObjectPeerId(users.get(i).user) == MessageObject.getObjectPeerId(p.user)) {\n"
            "                                hasSame = true;\n"
            "                                if (p.date > 0) {\n"
            "                                    users.get(i).date = p.date;\n"
            "                                }\n"
            "                                break;\n"
            "                            }\n"
            "                        }\n"
            "                        if (!hasSame) {\n"
            "                            users.add(p);\n"
            "                        }\n"
            "                    }\n"
            "                    if (seenCallback != null) {\n"
            "                        seenCallback.accept(usersRes);\n"
            "                    }\n"
            "                    loadReactions();\n"
            "                });\n"
            "            } else loadReactions();"
        ),
        "ReactedHeaderView.java use shared ColgramReadReceiptsHelper fetcher",
        eol_patch_fn,
        marker="org.telegram.ui.ColgramReadReceiptsHelper.loadGroupReadParticipants(currentAccount, message, chat, false, res ->",
    )

    # 8. MessagePrivateSeenView.java — use ColgramReadReceiptsHelper (graceful MESSAGE_NOT_READ handling)
    _eol_replace(
        priv_seen_view,
        (
            "        TLRPC.TL_messages_getOutboxReadDate req = new TLRPC.TL_messages_getOutboxReadDate();\n"
            "        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);\n"
            "        req.msg_id = messageId;\n"
            "        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "            if (err != null) {\n"
            '                if ("USER_PRIVACY_RESTRICTED".equals(err.text)) {\n'
            "                    valueTextView.setText(LocaleController.getString(R.string.PmReadUnknown));\n"
            "                    premiumTextView.setVisibility(View.GONE);\n"
            '                } else if ("YOUR_PRIVACY_RESTRICTED".equals(err.text)) {\n'
            "                    isPremiumLocked = true;\n"
            "                    valueTextView.setText(LocaleController.getString(R.string.PmRead));\n"
            "                    premiumTextView.setText(LocaleController.getString(R.string.PmReadShowWhen));\n"
            "                } else {\n"
            '                    valueTextView.setText(LocaleController.getString("UnknownError"));\n'
            "                    premiumTextView.setVisibility(View.GONE);\n"
            "                    BulletinFactory.of(Bulletin.BulletinWindow.make(getContext()), resourcesProvider).showForError(err);\n"
            "                }\n"
            "            } else if (res instanceof TLRPC.TL_outboxReadDate) {\n"
            "                TLRPC.TL_outboxReadDate r = (TLRPC.TL_outboxReadDate) res;\n"
            "                valueTextView.setText(LocaleController.formatPmSeenDate(r.date));\n"
            "                premiumTextView.setVisibility(View.GONE);\n"
            "            }\n"
            "            valueLayout.animate().alpha(1f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(320).start();\n"
            "            loadingView.animate().alpha(0f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(320).start();\n\n"
            "            if (isPremiumLocked) {\n"
            "                setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 6, 0));\n"
            "                setOnClickListener(v -> showSheet(getContext(), currentAccount, dialogId, false, dismiss, this::request, resourcesProvider));\n"
            "            } else {\n"
            "                setBackground(null);\n"
            "                setOnClickListener(null);\n"
            "            }\n"
            "        }));"
        ),
        (
            "        org.telegram.ui.ColgramReadReceiptsHelper.loadPrivateReadDate(currentAccount, dialogId, messageId, false, pr -> {\n"
            "            if (pr.status == org.telegram.ui.ColgramReadReceiptsHelper.PrivateSeenResult.STATUS_OK) {\n"
            "                isPremiumLocked = false;\n"
            "                valueTextView.setText(LocaleController.formatPmSeenDate(pr.date));\n"
            "                premiumTextView.setVisibility(View.GONE);\n"
            "            } else if (pr.status == org.telegram.ui.ColgramReadReceiptsHelper.PrivateSeenResult.STATUS_MESSAGE_NOT_READ) {\n"
            "                isPremiumLocked = false;\n"
            "                valueTextView.setText(org.telegram.ui.ColgramReadReceiptsHelper.TEXT_NOT_READ_YET);\n"
            "                premiumTextView.setVisibility(View.GONE);\n"
            "            } else if (pr.status == org.telegram.ui.ColgramReadReceiptsHelper.PrivateSeenResult.STATUS_USER_PRIVACY_RESTRICTED) {\n"
            "                isPremiumLocked = false;\n"
            "                valueTextView.setText(LocaleController.getString(R.string.PmReadUnknown));\n"
            "                premiumTextView.setVisibility(View.GONE);\n"
            "            } else if (pr.status == org.telegram.ui.ColgramReadReceiptsHelper.PrivateSeenResult.STATUS_YOUR_PRIVACY_RESTRICTED) {\n"
            "                isPremiumLocked = true;\n"
            "                valueTextView.setText(LocaleController.getString(R.string.PmRead));\n"
            "                premiumTextView.setText(LocaleController.getString(R.string.PmReadShowWhen));\n"
            "            } else {\n"
            "                isPremiumLocked = false;\n"
            '                valueTextView.setText(LocaleController.getString("UnknownError"));\n'
            "                premiumTextView.setVisibility(View.GONE);\n"
            "                if (pr.rawError != null) {\n"
            "                    BulletinFactory.of(Bulletin.BulletinWindow.make(getContext()), resourcesProvider).showForError(pr.rawError);\n"
            "                }\n"
            "            }\n"
            "            valueLayout.animate().alpha(1f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(320).start();\n"
            "            loadingView.animate().alpha(0f).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(320).start();\n\n"
            "            if (isPremiumLocked) {\n"
            "                setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 6, 0));\n"
            "                setOnClickListener(v -> showSheet(getContext(), currentAccount, dialogId, false, dismiss, () -> {\n"
            "                    org.telegram.ui.ColgramReadReceiptsHelper.invalidateMessage(currentAccount, dialogId, messageId);\n"
            "                    request();\n"
            "                }, resourcesProvider));\n"
            "            } else {\n"
            "                setBackground(null);\n"
            "                setOnClickListener(null);\n"
            "            }\n"
            "        });"
        ),
        "MessagePrivateSeenView.java delegate to ColgramReadReceiptsHelper",
        eol_patch_fn,
        marker="org.telegram.ui.ColgramReadReceiptsHelper.loadPrivateReadDate(currentAccount, dialogId, messageId",
    )


def _patch_pillar2_outbox_truth(repo_path: str, eol_patch_fn) -> None:
    msg_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger")
    ui = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")

    mc_path = os.path.join(msg_dir, "MessagesController.java")
    ms_path = os.path.join(msg_dir, "MessagesStorage.java")
    dialog_cell = os.path.join(ui, "Cells", "DialogCell.java")
    msg_cell = os.path.join(ui, "Cells", "ChatMessageCell.java")
    chat_act = os.path.join(ui, "ChatActivity.java")

    # 1. MessagesController.isOutboxMessageRead(dialogId, msgId, messageOwnerUnread)
    _eol_replace(
        mc_path,
        "    public ConcurrentHashMap<Long, Integer> dialogs_read_outbox_max = new ConcurrentHashMap<>(100, 1.0f, 2);",
        (
            "    public ConcurrentHashMap<Long, Integer> dialogs_read_outbox_max = new ConcurrentHashMap<>(100, 1.0f, 2);\n\n"
            "    public boolean isOutboxMessageRead(long dialogId, int msgId, boolean messageOwnerUnread) {\n"
            "        if (!messageOwnerUnread) {\n"
            "            return true;\n"
            "        }\n"
            "        if (msgId <= 0) {\n"
            "            return false;\n"
            "        }\n"
            "        Integer maxVal = dialogs_read_outbox_max.get(dialogId);\n"
            "        int maxOut = maxVal != null ? maxVal : 0;\n"
            "        TLRPC.Dialog dialog = dialogs_dict.get(dialogId);\n"
            "        if (dialog != null && dialog.read_outbox_max_id > maxOut) {\n"
            "            maxOut = dialog.read_outbox_max_id;\n"
            "        }\n"
            "        return maxOut >= msgId;\n"
            "    }"
        ),
        "MessagesController.isOutboxMessageRead single source of truth",
        eol_patch_fn,
        marker="public boolean isOutboxMessageRead(long dialogId, int msgId, boolean messageOwnerUnread)",
    )

    # 2. DialogCell.java outbox checkmark
    _eol_replace(
        dialog_cell,
        (
            "                        } else if (isDialogCell) {\n"
            "                            drawCheck1 = (readOutboxMaxId > 0 && readOutboxMaxId >= message.getId()) || !message.isUnread() || ChatObject.isChannel(chat) && !chat.megagroup;\n"
            "                        } else {\n"
            "                            drawCheck1 = !message.isUnread() || ChatObject.isChannel(chat) && !chat.megagroup;\n"
            "                        }"
        ),
        (
            "                        } else if (isDialogCell) {\n"
            "                            drawCheck1 = MessagesController.getInstance(currentAccount).isOutboxMessageRead(currentDialogId, message.getId(), message.isUnread()) || (readOutboxMaxId > 0 && readOutboxMaxId >= message.getId()) || ChatObject.isChannel(chat) && !chat.megagroup;\n"
            "                        } else {\n"
            "                            drawCheck1 = MessagesController.getInstance(currentAccount).isOutboxMessageRead(currentDialogId, message.getId(), message.isUnread()) || ChatObject.isChannel(chat) && !chat.megagroup;\n"
            "                        }"
        ),
        "DialogCell.java use MessagesController.isOutboxMessageRead",
        eol_patch_fn,
        marker="MessagesController.getInstance(currentAccount).isOutboxMessageRead(currentDialogId, message.getId(), message.isUnread())",
    )

    # 3. ChatMessageCell.java outbox checkmark + accessibility consistency
    _eol_replace(
        msg_cell,
        (
            "                } else if (currentMessageObject.isSent()) {\n"
            "                    if (!currentMessageObject.scheduled && !currentMessageObject.isUnread()) {\n"
            "                        drawCheck1 = true;\n"
            "                    } else {\n"
            "                        drawCheck1 = false;\n"
            "                    }"
        ),
        (
            "                } else if (currentMessageObject.isSent()) {\n"
            "                    if (!currentMessageObject.scheduled && MessagesController.getInstance(currentAccount).isOutboxMessageRead(currentMessageObject.getDialogId(), currentMessageObject.getId(), currentMessageObject.isUnread())) {\n"
            "                        drawCheck1 = true;\n"
            "                    } else {\n"
            "                        drawCheck1 = false;\n"
            "                    }"
        ),
        "ChatMessageCell.java computeCheckStatus use isOutboxMessageRead",
        eol_patch_fn,
        marker="!currentMessageObject.scheduled && MessagesController.getInstance(currentAccount).isOutboxMessageRead(",
    )
    _eol_replace(
        msg_cell,
        "                final boolean unread = currentMessageObject != null && currentMessageObject.isOut() && !currentMessageObject.scheduled && currentMessageObject.isUnread();",
        "                final boolean unread = currentMessageObject != null && currentMessageObject.isOut() && !currentMessageObject.scheduled && !MessagesController.getInstance(currentAccount).isOutboxMessageRead(currentMessageObject.getDialogId(), currentMessageObject.getId(), currentMessageObject.isUnread());",
        "ChatMessageCell.java accessibility unread flag use isOutboxMessageRead",
        eol_patch_fn,
        marker="!currentMessageObject.scheduled && !MessagesController.getInstance(currentAccount).isOutboxMessageRead(",
    )
    _eol_replace(
        msg_cell,
        '                                sb.append(currentMessageObject.isUnread() ? getString("AccDescrMsgUnread", R.string.AccDescrMsgUnread) : getString("AccDescrMsgRead", R.string.AccDescrMsgRead));',
        '                                sb.append(!MessagesController.getInstance(currentAccount).isOutboxMessageRead(currentMessageObject.getDialogId(), currentMessageObject.getId(), currentMessageObject.isUnread()) ? getString("AccDescrMsgUnread", R.string.AccDescrMsgUnread) : getString("AccDescrMsgRead", R.string.AccDescrMsgRead));',
        "ChatMessageCell.java accessibility status label use isOutboxMessageRead",
        eol_patch_fn,
        marker='sb.append(!MessagesController.getInstance(currentAccount).isOutboxMessageRead(',
    )

    # 4. ChatActivity.java load reconciliation for ALL dialogs + filteredMessages outbox update
    _eol_replace(
        chat_act,
        (
            "            if (threadMessageId != 0) {\n"
            "                if (messageId <= (obj.isOut() ? threadMaxOutboxReadId : threadMaxInboxReadId)) {\n"
            "                    obj.setIsRead();\n"
            "                }\n"
            "            } else if (ChatObject.isForum(currentChat) || ChatObject.isMonoForum(currentChat) && ChatObject.canManageMonoForum(currentAccount, currentChat)) {\n"
            "                long topicId = ChatObject.isForum(currentChat) ? MessageObject.getTopicId(currentAccount, obj.messageOwner, true) : MessageObject.getMonoForumTopicId(obj.messageOwner);\n"
            "                TLRPC.TL_forumTopic topic = getMessagesController().getTopicsController().findTopic(currentChat.id, topicId);\n"
            "                if (topic != null) {\n"
            "                    if (messageId <= (obj.isOut() ? topic.read_outbox_max_id : topic.read_inbox_max_id)) {\n"
            "                        obj.setIsRead();\n"
            "                    }\n"
            "                }\n"
            "            }"
        ),
        (
            "            if (threadMessageId != 0) {\n"
            "                if (messageId <= (obj.isOut() ? threadMaxOutboxReadId : threadMaxInboxReadId)) {\n"
            "                    obj.setIsRead();\n"
            "                }\n"
            "            } else if (ChatObject.isForum(currentChat) || ChatObject.isMonoForum(currentChat) && ChatObject.canManageMonoForum(currentAccount, currentChat)) {\n"
            "                long topicId = ChatObject.isForum(currentChat) ? MessageObject.getTopicId(currentAccount, obj.messageOwner, true) : MessageObject.getMonoForumTopicId(obj.messageOwner);\n"
            "                TLRPC.TL_forumTopic topic = getMessagesController().getTopicsController().findTopic(currentChat.id, topicId);\n"
            "                if (topic != null) {\n"
            "                    if (messageId <= (obj.isOut() ? topic.read_outbox_max_id : topic.read_inbox_max_id)) {\n"
            "                        obj.setIsRead();\n"
            "                    }\n"
            "                }\n"
            "            } else if (messageId > 0) {\n"
            "                if (obj.isOut()) {\n"
            "                    if (getMessagesController().isOutboxMessageRead(dialog_id, messageId, obj.isUnread())) {\n"
            "                        obj.setIsRead();\n"
            "                    }\n"
            "                } else {\n"
            "                    Integer colgramInMax = getMessagesController().dialogs_read_inbox_max.get(dialog_id);\n"
            "                    int colgramLocalIn = org.colgram.core.ColgramLocalReadStore.getLocalReadMax(ApplicationLoader.applicationContext, currentAccount, dialog_id);\n"
            "                    int colgramEffIn = Math.max(colgramInMax != null ? colgramInMax : 0, colgramLocalIn);\n"
            "                    if (colgramEffIn > 0 && messageId <= colgramEffIn) {\n"
            "                        obj.setIsRead();\n"
            "                    }\n"
            "                }\n"
            "            }"
        ),
        "ChatActivity.java reconcile outbox & local inbox read flags on load for all dialogs",
        eol_patch_fn,
        marker="getMessagesController().isOutboxMessageRead(dialog_id, messageId, obj.isUnread())",
    )
    _eol_replace(
        chat_act,
        (
            "                    for (int a = 0, size2 = messages.size(); a < size2; a++) {\n"
            "                        MessageObject obj = messages.get(a);\n"
            "                        if (obj.isOut() && obj.getId() > 0 && obj.getId() <= messageId) {\n"
            "                            obj.setIsRead();\n"
            "                            if (chatAdapter != null) {\n"
            "                                chatAdapter.invalidateRowWithMessageObject(obj);\n"
            "                            }\n"
            "                        }\n"
            "                    }"
        ),
        (
            "                    for (int a = 0, size2 = messages.size(); a < size2; a++) {\n"
            "                        MessageObject obj = messages.get(a);\n"
            "                        if (obj.isOut() && obj.getId() > 0 && obj.getId() <= messageId) {\n"
            "                            obj.setIsRead();\n"
            "                            if (chatAdapter != null) {\n"
            "                                chatAdapter.invalidateRowWithMessageObject(obj);\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                    if (chatAdapter != null && chatAdapter.filteredMessages != null && !chatAdapter.filteredMessages.isEmpty()) {\n"
            "                        for (int a = 0, size2 = chatAdapter.filteredMessages.size(); a < size2; a++) {\n"
            "                            MessageObject obj = chatAdapter.filteredMessages.get(a);\n"
            "                            if (obj != null && obj.isOut() && obj.getId() > 0 && obj.getId() <= messageId) {\n"
            "                                obj.setIsRead();\n"
            "                                chatAdapter.invalidateRowWithMessageObject(obj);\n"
            "                            }\n"
            "                        }\n"
            "                    }"
        ),
        "ChatActivity.java update filteredMessages on outbox messagesRead",
        eol_patch_fn,
        marker="chatAdapter.filteredMessages != null && !chatAdapter.filteredMessages.isEmpty()) {",
    )

    # 5. MessagesStorage.markMessagesAsReadInternal persist monotonic dialogs.outbox_max
    _eol_replace(
        ms_path,
        (
            "            if (!isEmpty(outbox)) {\n"
            "                for (int b = 0; b < outbox.size(); b++) {\n"
            "                    long key = outbox.keyAt(b);\n"
            "                    int messageId = outbox.get(key);\n"
            '                    database.executeFast(String.format(Locale.US, "UPDATE messages_v2 SET read_state = read_state | 1 WHERE uid = %d AND mid > 0 AND mid <= %d AND read_state IN(0,2) AND out = 1", key, messageId)).stepThis().dispose();\n'
            "                }\n"
            "            }"
        ),
        (
            "            if (!isEmpty(outbox)) {\n"
            "                for (int b = 0; b < outbox.size(); b++) {\n"
            "                    long key = outbox.keyAt(b);\n"
            "                    int messageId = outbox.get(key);\n"
            '                    database.executeFast(String.format(Locale.US, "UPDATE messages_v2 SET read_state = read_state | 1 WHERE uid = %d AND mid > 0 AND mid <= %d AND read_state IN(0,2) AND out = 1", key, messageId)).stepThis().dispose();\n'
            '                    database.executeFast(String.format(Locale.US, "UPDATE dialogs SET outbox_max = MAX(outbox_max, %d) WHERE did = %d", messageId, key)).stepThis().dispose();\n'
            "                }\n"
            "            }"
        ),
        "MessagesStorage.markMessagesAsReadInternal persist monotonic dialogs.outbox_max",
        eol_patch_fn,
        marker='UPDATE dialogs SET outbox_max = MAX(outbox_max, %d) WHERE did = %d',
    )


def _patch_pillar3_local_read_watermark(repo_path: str, eol_patch_fn) -> None:
    msg_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger")
    mc_path = os.path.join(msg_dir, "MessagesController.java")
    ms_path = os.path.join(msg_dir, "MessagesStorage.java")

    # 1. Shared helper on MessagesController to clamp synced dialogs against ColgramLocalReadStore
    _eol_replace(
        mc_path,
        "    public ConcurrentHashMap<Long, Integer> dialogs_read_inbox_max = new ConcurrentHashMap<>(100, 1.0f, 2);",
        (
            "    public ConcurrentHashMap<Long, Integer> dialogs_read_inbox_max = new ConcurrentHashMap<>(100, 1.0f, 2);\n\n"
            "    private void colgramClampDialogLocalRead(TLRPC.Dialog d) {\n"
            "        if (d == null || DialogObject.isEncryptedDialog(d.id)) {\n"
            "            return;\n"
            "        }\n"
            "        int localMax = org.colgram.core.ColgramLocalReadStore.getLocalReadMax(ApplicationLoader.applicationContext, currentAccount, d.id);\n"
            "        if (localMax <= 0) {\n"
            "            return;\n"
            "        }\n"
            "        if (d.read_inbox_max_id >= localMax) {\n"
            "            org.colgram.core.ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, d.id, d.read_inbox_max_id);\n"
            "        } else {\n"
            "            d.read_inbox_max_id = localMax;\n"
            "            if (d.top_message <= localMax) {\n"
            "                d.unread_count = 0;\n"
            "            } else {\n"
            "                d.unread_count = Math.min(d.unread_count, Math.max(0, d.top_message - localMax));\n"
            "            }\n"
            "        }\n"
            "    }"
        ),
        "MessagesController.colgramClampDialogLocalRead helper",
        eol_patch_fn,
        marker="private void colgramClampDialogLocalRead(TLRPC.Dialog d)",
    )

    # 2. markDialogAsRead: handle perf#3/legacy cleanly, record local watermark, update dialog/messages/topics
    _upgrade_or_patch(
        mc_path,
        (
            (
                "        final boolean colgramGhostRead = org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId);\n"
                "        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);\n"
                "        boolean createReadTask;\n\n"
                "        if (threadId != 0) {\n"
                "            createReadTask = maxPositiveId != Integer.MAX_VALUE;"
            ),
        ),
        (
            "        final boolean colgramGhostRead = org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId);\n"
            "        boolean createReadTask;\n\n"
            "        if (threadId != 0) {\n"
            "            createReadTask = maxPositiveId != Integer.MAX_VALUE;"
        ),
        (
            "        // colgram-local-read-symbiosis\n"
            "        final boolean colgramGhostRead = org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId);\n"
            "        int colgramEffMaxId = maxPositiveId;\n"
            "        if (colgramEffMaxId == Integer.MAX_VALUE) {\n"
            "            TLRPC.Dialog colgramTmpD = dialogs_dict.get(dialogId);\n"
            "            colgramEffMaxId = colgramTmpD != null ? Math.max(colgramTmpD.top_message, 0) : 0;\n"
            "        }\n"
            "        boolean createReadTask;\n\n"
            "        if (threadId != 0) {\n"
            "            if (colgramGhostRead && !DialogObject.isEncryptedDialog(dialogId) && colgramEffMaxId > 0) {\n"
            "                getMessagesStorage().updateRepliesMaxReadId(dialogId < 0 ? -dialogId : dialogId, threadId, colgramEffMaxId, 0, true);\n"
            "                if (dialogId < 0) {\n"
            "                    getTopicsController().updateMaxReadId(-dialogId, threadId, colgramEffMaxId, 0, 0);\n"
            "                }\n"
            "            }\n"
            "            createReadTask = maxPositiveId != Integer.MAX_VALUE;"
        ),
        "MessagesController.markDialogAsRead topic & effective maxId symbiosis",
        eol_patch_fn,
        marker="// colgram-local-read-symbiosis",
    )
    _eol_replace(
        mc_path,
        (
            "        if (colgramGhostRead) {\n"
            "            // Ghost mode: skip the outgoing receipt entirely. Local read state above has\n"
            "            // already been updated, so the chat reads correctly on this device.\n"
            "            return;\n"
            "        }"
        ),
        (
            "        if (colgramGhostRead) {\n"
            "            if (!DialogObject.isEncryptedDialog(dialogId) && threadId == 0 && colgramEffMaxId > 0) {\n"
            "                org.colgram.core.ColgramLocalReadStore.recordLocalRead(ApplicationLoader.applicationContext, currentAccount, dialogId, colgramEffMaxId);\n"
            "                TLRPC.Dialog ghostDlg = dialogs_dict.get(dialogId);\n"
            "                if (ghostDlg != null && colgramEffMaxId > ghostDlg.read_inbox_max_id) {\n"
            "                    ghostDlg.read_inbox_max_id = colgramEffMaxId;\n"
            "                }\n"
            "                ArrayList<MessageObject> dlgMsgs = dialogMessage.get(dialogId);\n"
            "                if (dlgMsgs != null) {\n"
            "                    for (int gi = 0; gi < dlgMsgs.size(); gi++) {\n"
            "                        MessageObject gm = dlgMsgs.get(gi);\n"
            "                        if (gm != null && !gm.isOut() && gm.getId() > 0 && gm.getId() <= colgramEffMaxId) {\n"
            "                            gm.setIsRead();\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "                final LongSparseIntArray localInboxMap = new LongSparseIntArray(1);\n"
            "                localInboxMap.put(dialogId, colgramEffMaxId);\n"
            "                AndroidUtilities.runOnUIThread(() -> {\n"
            "                    getNotificationCenter().postNotificationName(NotificationCenter.messagesRead, localInboxMap, null);\n"
            "                });\n"
            "            }\n"
            "            return;\n"
            "        }\n"
            "        if (!DialogObject.isEncryptedDialog(dialogId) && threadId == 0 && colgramEffMaxId > 0) {\n"
            "            org.colgram.core.ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, dialogId, colgramEffMaxId);\n"
            "        }"
        ),
        "MessagesController.markDialogAsRead persist monotonic watermark & notify local read",
        eol_patch_fn,
        marker="ColgramLocalReadStore.recordLocalRead(",
    )

    # 3. Clear on markDialogAsUnread + server catch-up updates
    _eol_replace(
        mc_path,
        (
            "    public void markDialogAsUnread(long dialogId, TLRPC.InputPeer peer, long taskId) {\n"
            "        TLRPC.Dialog dialog = dialogs_dict.get(dialogId);"
        ),
        (
            "    public void markDialogAsUnread(long dialogId, TLRPC.InputPeer peer, long taskId) {\n"
            "        org.colgram.core.ColgramLocalReadStore.clearDialog(ApplicationLoader.applicationContext, currentAccount, dialogId);\n"
            "        TLRPC.Dialog dialog = dialogs_dict.get(dialogId);"
        ),
        "MessagesController.markDialogAsUnread clear local read watermark",
        eol_patch_fn,
        marker="ColgramLocalReadStore.clearDialog(ApplicationLoader.applicationContext, currentAccount, dialogId);",
    )
    _eol_replace(
        mc_path,
        (
            "                dialogs_read_inbox_max.put(dialogId, Math.max(value, update.max_id));\n"
            "            } else if (baseUpdate instanceof TL_update.TL_updateReadHistoryOutbox) {"
        ),
        (
            "                dialogs_read_inbox_max.put(dialogId, Math.max(value, update.max_id));\n"
            "                org.colgram.core.ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, dialogId, update.max_id);\n"
            "            } else if (baseUpdate instanceof TL_update.TL_updateReadHistoryOutbox) {"
        ),
        "MessagesController TL_updateReadHistoryInbox prune watermark on catch-up",
        eol_patch_fn,
        marker="ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, dialogId, update.max_id);\n            } else if (baseUpdate instanceof TL_update.TL_updateReadHistoryOutbox)",
    )
    _eol_replace(
        mc_path,
        (
            "                stillUnreadMessagesCount.put(dialogId, update.still_unread_count);\n"
            "                dialogs_read_inbox_max.put(dialogId, Math.max(value, update.max_id));\n"
            '                FileLog.d("TL_updateReadChannelInbox'
        ),
        (
            "                stillUnreadMessagesCount.put(dialogId, update.still_unread_count);\n"
            "                dialogs_read_inbox_max.put(dialogId, Math.max(value, update.max_id));\n"
            "                org.colgram.core.ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, dialogId, update.max_id);\n"
            '                FileLog.d("TL_updateReadChannelInbox'
        ),
        "MessagesController TL_updateReadChannelInbox prune watermark on catch-up",
        eol_patch_fn,
        marker='ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, dialogId, update.max_id);\n                FileLog.d("TL_updateReadChannelInbox',
    )

    # 4. Clamp in processLoadedDialogs (before new_dialogs_dict / dialogs_read_inbox_max AND before dialogs_dict.put) & getPinnedDialogs
    _eol_replace(
        mc_path,
        (
            "                new_dialogs_dict.put(d.id, d);\n\n"
            "                if (allowCheck && loadType == DIALOGS_LOAD_TYPE_CACHE && (d.read_outbox_max_id == 0 || d.read_inbox_max_id == 0) && d.top_message != 0) {"
        ),
        (
            "                colgramClampDialogLocalRead(d);\n"
            "                new_dialogs_dict.put(d.id, d);\n\n"
            "                if (allowCheck && loadType == DIALOGS_LOAD_TYPE_CACHE && (d.read_outbox_max_id == 0 || d.read_inbox_max_id == 0) && d.top_message != 0) {"
        ),
        "MessagesController.processLoadedDialogs clamp synced dialog against local watermark",
        eol_patch_fn,
        marker="colgramClampDialogLocalRead(d);\n                new_dialogs_dict.put(d.id, d);",
    )
    _eol_replace(
        mc_path,
        (
            "                    if (currentDialog == null) {\n"
            "                        added = true;\n"
            "                        dialogs_dict.put(key, value);"
        ),
        (
            "                    colgramClampDialogLocalRead(value);\n"
            "                    if (currentDialog == null) {\n"
            "                        added = true;\n"
            "                        dialogs_dict.put(key, value);"
        ),
        "MessagesController.processLoadedDialogs clamp before dialogs_dict.put",
        eol_patch_fn,
        marker="colgramClampDialogLocalRead(value);\n                    if (currentDialog == null) {",
    )
    _eol_replace(
        mc_path,
        (
            "                    Integer value = dialogs_read_inbox_max.get(d.id);\n"
            "                    if (value == null) {\n"
            "                        value = 0;\n"
            "                    }\n"
            "                    dialogs_read_inbox_max.put(d.id, Math.max(value, d.read_inbox_max_id));\n\n"
            "                    value = dialogs_read_outbox_max.get(d.id);"
        ),
        (
            "                    colgramClampDialogLocalRead(d);\n"
            "                    Integer value = dialogs_read_inbox_max.get(d.id);\n"
            "                    if (value == null) {\n"
            "                        value = 0;\n"
            "                    }\n"
            "                    dialogs_read_inbox_max.put(d.id, Math.max(value, d.read_inbox_max_id));\n\n"
            "                    value = dialogs_read_outbox_max.get(d.id);"
        ),
        "MessagesController.getPinnedDialogs clamp synced pinned dialog against local watermark",
        eol_patch_fn,
        marker="colgramClampDialogLocalRead(d);\n                    Integer value = dialogs_read_inbox_max.get(d.id);",
    )

    # 5. Clamp in MessagesStorage.putDialogsInternal & TL_channelFull handler
    _eol_replace(
        ms_path,
        (
            "                    state_dialogs.requery();\n"
            "                    state_dialogs.bindLong(1, dialog.id);\n"
            "                    state_dialogs.bindInteger(2, dialog instanceof TLRPC.TL_dialogCommunity ? dialog.last_message_date : messageDate);\n"
            "                    state_dialogs.bindInteger(3, dialog.unread_count);"
        ),
        (
            "                    int colgramLocalMax = !DialogObject.isEncryptedDialog(dialog.id)\n"
            "                            ? org.colgram.core.ColgramLocalReadStore.getLocalReadMax(ApplicationLoader.applicationContext, currentAccount, dialog.id)\n"
            "                            : 0;\n"
            "                    if (colgramLocalMax > 0) {\n"
            "                        if (dialog.read_inbox_max_id >= colgramLocalMax) {\n"
            "                            org.colgram.core.ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, dialog.id, dialog.read_inbox_max_id);\n"
            "                        } else {\n"
            "                            dialog.read_inbox_max_id = colgramLocalMax;\n"
            "                            if (dialog.top_message <= colgramLocalMax) {\n"
            "                                dialog.unread_count = 0;\n"
            "                            } else if (dialog.unread_count > 0) {\n"
            "                                int colgramUnread = 0;\n"
            "                                SQLiteCursor colgramUc = null;\n"
            "                                try {\n"
            '                                    colgramUc = database.queryFinalized("SELECT COUNT(*) FROM messages_v2 WHERE uid = ? AND mid > ? AND out = 0 AND read_state IN(0,2)", dialog.id, colgramLocalMax);\n'
            "                                    if (colgramUc.next()) {\n"
            "                                        colgramUnread = colgramUc.intValue(0);\n"
            "                                    }\n"
            "                                } finally {\n"
            "                                    if (colgramUc != null) {\n"
            "                                        colgramUc.dispose();\n"
            "                                    }\n"
            "                                }\n"
            "                                dialog.unread_count = Math.min(dialog.unread_count, colgramUnread);\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                    state_dialogs.requery();\n"
            "                    state_dialogs.bindLong(1, dialog.id);\n"
            "                    state_dialogs.bindInteger(2, dialog instanceof TLRPC.TL_dialogCommunity ? dialog.last_message_date : messageDate);\n"
            "                    state_dialogs.bindInteger(3, dialog.unread_count);"
        ),
        "MessagesStorage.putDialogsInternal clamp dialogs against ColgramLocalReadStore",
        eol_patch_fn,
        marker="ColgramLocalReadStore.getLocalReadMax(ApplicationLoader.applicationContext, currentAccount, dialog.id)",
    )
    _eol_replace(
        ms_path,
        (
            "                if (info instanceof TLRPC.TL_channelFull) {\n"
            '                    cursor = database.queryFinalized("SELECT inbox_max, outbox_max FROM dialogs WHERE did = " + (-info.id));'
        ),
        (
            "                if (info instanceof TLRPC.TL_channelFull) {\n"
            "                    int colgramChannelLocalMax = org.colgram.core.ColgramLocalReadStore.getLocalReadMax(ApplicationLoader.applicationContext, currentAccount, -info.id);\n"
            "                    if (colgramChannelLocalMax > 0) {\n"
            "                        if (info.read_inbox_max_id >= colgramChannelLocalMax) {\n"
            "                            org.colgram.core.ColgramLocalReadStore.clearIfCaughtUp(ApplicationLoader.applicationContext, currentAccount, -info.id, info.read_inbox_max_id);\n"
            "                        } else {\n"
            "                            info.read_inbox_max_id = colgramChannelLocalMax;\n"
            "                            if (info.unread_count > 0) {\n"
            "                                int colgramUnread = 0;\n"
            "                                SQLiteCursor colgramUc = null;\n"
            "                                try {\n"
            '                                    colgramUc = database.queryFinalized("SELECT COUNT(*) FROM messages_v2 WHERE uid = ? AND mid > ? AND out = 0 AND read_state IN(0,2)", -info.id, colgramChannelLocalMax);\n'
            "                                    if (colgramUc.next()) {\n"
            "                                        colgramUnread = colgramUc.intValue(0);\n"
            "                                    }\n"
            "                                } finally {\n"
            "                                    if (colgramUc != null) {\n"
            "                                        colgramUc.dispose();\n"
            "                                    }\n"
            "                                }\n"
            "                                info.unread_count = Math.min(info.unread_count, colgramUnread);\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            '                    cursor = database.queryFinalized("SELECT inbox_max, outbox_max FROM dialogs WHERE did = " + (-info.id));'
        ),
        "MessagesStorage TL_channelFull clamp read_inbox_max_id & unread_count",
        eol_patch_fn,
        marker="int colgramChannelLocalMax = org.colgram.core.ColgramLocalReadStore.getLocalReadMax(",
    )

    # 6. Firewall hygiene: guard markPollVotesAsRead + guard res.pts > 0 before processNewDifferenceParams on read callbacks
    _eol_replace(
        mc_path,
        (
            "        getMessagesStorage().updateUnreadPollVotesCount(dialogId, topicId, 0);\n"
            "        TLRPC.TL_messages_readPollVotes req = new TLRPC.TL_messages_readPollVotes();"
        ),
        (
            "        getMessagesStorage().updateUnreadPollVotesCount(dialogId, topicId, 0);\n"
            "        if (org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId)) {\n"
            "            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, UPDATE_MASK_REACTIONS_READ);\n"
            "            return;\n"
            "        }\n"
            "        TLRPC.TL_messages_readPollVotes req = new TLRPC.TL_messages_readPollVotes();"
        ),
        "MessagesController.markPollVotesAsRead ghost guard",
        eol_patch_fn,
        marker="if (org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId)) {\n            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, UPDATE_MASK_REACTIONS_READ);",
    )
    _eol_replace(
        mc_path,
        (
            "                getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                    if (error == null) {\n"
            "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                        processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                    }\n"
            "                });"
        ),
        (
            "                getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                    if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {\n"
            "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                        if (res.pts > 0) {\n"
            "                            processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                        }\n"
            "                    }\n"
            "                });"
        ),
        "MessagesController readMessageContents res.pts > 0 guard (contents)",
        eol_patch_fn,
        marker="if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {\n                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n                        if (res.pts > 0) {\n                            processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n                        }\n                    }\n                });",
    )
    _eol_replace(
        mc_path,
        (
            "            getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                if (error == null) {\n"
            "                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                    processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                }\n"
            "                if (newTaskId != 0) {"
        ),
        (
            "            getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {\n"
            "                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                    if (res.pts > 0) {\n"
            "                        processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                    }\n"
            "                }\n"
            "                if (newTaskId != 0) {"
        ),
        "MessagesController doDeleteShowOnceTask res.pts > 0 guard",
        eol_patch_fn,
        marker="if (res.pts > 0) {\n                        processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n                    }\n                }\n                if (newTaskId != 0) {",
    )
    _eol_replace(
        mc_path,
        (
            "            getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                if (error == null) {\n"
            "                    if (response instanceof TLRPC.TL_messages_affectedMessages) {\n"
            "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                        processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                    }\n"
            "                }\n"
            "            });"
        ),
        (
            "            getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                if (error == null) {\n"
            "                    if (response instanceof TLRPC.TL_messages_affectedMessages) {\n"
            "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                        if (res.pts > 0) {\n"
            "                            processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "            });"
        ),
        "MessagesController completeReadTask res.pts > 0 guard",
        eol_patch_fn,
        marker="if (res.pts > 0) {\n                            processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n                        }\n                    }\n                }\n            });",
    )
    # Also guard the first MessagesController readMessageContents (lines 14820-14825) if identical string had two occurrences!
    if os.path.isfile(mc_path):
        with open(mc_path, "r", encoding="utf-8", errors="ignore", newline="") as _mf:
            _mc_norm = _mf.read().replace("\r\n", "\n")
        _raw_mc_read = (
            "                getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                    if (error == null) {\n"
            "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                        processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                    }\n"
            "                });"
        )
        _new_mc_read = (
            "                getConnectionsManager().sendRequest(req, (response, error) -> {\n"
            "                    if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {\n"
            "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
            "                        if (res.pts > 0) {\n"
            "                            processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
            "                        }\n"
            "                    }\n"
            "                });"
        )
        if _raw_mc_read in _mc_norm:
            eol_patch_fn(mc_path, _raw_mc_read, _new_mc_read, "MessagesController markMentionMessageAsRead res.pts > 0 guard")


def apply(repo_path: str, patch_file_fn, eol_patch_fn) -> None:
    """Apply all Unified Read-Receipts, Outbox Read Truth, and Local Ghost-Read Watermark patches idempotently."""
    _ = patch_file_fn
    _patch_pillar1_read_receipts(repo_path, eol_patch_fn)
    _patch_pillar2_outbox_truth(repo_path, eol_patch_fn)
    _patch_pillar3_local_read_watermark(repo_path, eol_patch_fn)
