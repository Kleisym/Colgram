# -*- coding: utf-8 -*-
"""Contract and 2-pass idempotency tests for Unified Read-Receipts, Outbox Truth, and Local Ghost-Read Watermarks."""

import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
TEMPLATES = SCRIPTS / "templates"
sys.path.insert(0, str(SCRIPTS))


class ReadSymbiosisContractsTest(unittest.TestCase):
    def test_core_and_templates_symbols_present(self):
        lrs = (CORE / "ColgramLocalReadStore.java").read_text(encoding="utf-8")
        for token in (
            "public static boolean recordLocalRead(",
            "public static int getLocalReadMax(",
            "public static boolean clearIfCaughtUp(",
            "public static void clearDialog(",
            "public static void resetForTests()",
        ):
            self.assertIn(token, lrs)
        self.assertNotIn("import org.telegram.", lrs)
        self.assertNotIn("org.telegram.", lrs)
        self.assertNotIn("TLRPC", lrs)

        rrh = (TEMPLATES / "ColgramReadReceiptsHelper.java").read_text(encoding="utf-8")
        for token in (
            "CACHE_TTL_MS = 15_000L",
            'TEXT_NOT_READ_YET = "Ещё не прочитано"',
            "public static boolean canShowReadReceipts(",
            "public static boolean canShowReadReceiptsForMessage(",
            "public static int loadGroupReadParticipants(",
            "public static int loadPrivateReadDate(",
            "mc.isOutboxMessageRead(",
            "ms.getUsers(",
            "ms.getChats(",
            "createPlaceholderObject(",
        ):
            self.assertIn(token, rrh)

        seen_sheet = (TEMPLATES / "ColgramMessageSeenSheet.java").read_text(encoding="utf-8")
        self.assertIn("ColgramReadReceiptsHelper.canShowReadReceipts(", seen_sheet)
        self.assertIn("ColgramReadReceiptsHelper.canShowReadReceiptsForMessage(", seen_sheet)
        self.assertIn("ColgramReadReceiptsHelper.loadGroupReadParticipants(", seen_sheet)
        self.assertIn("ColgramReadReceiptsHelper.loadPrivateReadDate(", seen_sheet)
        self.assertIn("MessageSeenView.UserCell", seen_sheet)
        self.assertNotIn("AlertDialog.Builder", seen_sheet)

    def test_read_symbiosis_patches_two_pass_idempotency(self):
        import read_symbiosis_patches as rsp

        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "Telegram-Src"
            base = repo / "TMessagesProj/src/main/java/org/telegram"
            msg = base / "messenger"
            ui = base / "ui"
            cells = ui / "Cells"
            comps = ui / "Components"
            for d in (msg, ui, cells, comps):
                d.mkdir(parents=True, exist_ok=True)

            (ui / "ChatActivity.java").write_text(
                "            final boolean colgramCircleOrVoice = message.isRoundVideo() || message.isVoice();\n"
                "            final boolean showMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && !isInScheduleMode() && currentChat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && (!message.isContentUnread() || colgramCircleOrVoice) && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().chatReadMarkExpirePeriod) && (ChatObject.isMegagroup(currentChat) || !ChatObject.isChannel(currentChat)) && (chatInfo == null || chatInfo.participants_count <= getMessagesController().chatReadMarkSizeThreshold) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) && chatMode != MODE_SAVED && message.canSetReaction() && !ChatObject.isMonoForum(currentChat);\n"
                "            final boolean showMessageAuthor = !suggestEdit && !isEphemeral && currentChat != null && !message.isOut() && ChatObject.isMonoForum(currentChat) && ChatObject.canManageMonoForum(currentAccount, currentChat) && -currentChat.linked_monoforum_id == message.getFromChatId();\n"
                "            final boolean showPrivateMessageSeen = !suggestEdit && !isEphemeral && !isReactionsViewAvailable && currentChat == null && currentEncryptedChat == null && (currentUser != null && !UserObject.isUserSelf(currentUser) && !UserObject.isReplyUser(currentUser) && !UserObject.isAnonymous(currentUser) && !currentUser.bot && !UserObject.isService(currentUser.id)) && (userInfo == null || !userInfo.read_dates_private) && !isInScheduleMode() && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && (!message.isContentUnread() || colgramCircleOrVoice) && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < getMessagesController().pmReadDateExpirePeriod) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);\n"
                "                    boolean showSeenBtn = ColgramMessageSeenSheet.canCheckSeen(currentAccount, singleSel, currentChat, currentUser);\n"
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
                "            }\n"
                "                    for (int a = 0, size2 = messages.size(); a < size2; a++) {\n"
                "                        MessageObject obj = messages.get(a);\n"
                "                        if (obj.isOut() && obj.getId() > 0 && obj.getId() <= messageId) {\n"
                "                            obj.setIsRead();\n"
                "                            if (chatAdapter != null) {\n"
                "                                chatAdapter.invalidateRowWithMessageObject(obj);\n"
                "                            }\n"
                "                        }\n"
                "                    }\n",
                encoding="utf-8",
            )
            (ui / "PhotoViewer.java").write_text(
                "                if (currentMessageObject != null && ColgramMessageSeenSheet.canCheckSeenForMessage(currentAccount, currentMessageObject)) {\n",
                encoding="utf-8",
            )
            _menu_src = (
                "        final boolean showMessageSeen = !isReactionsViewAvailable && !chatActivity.isInScheduleMode() && chatActivity.currentChat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(chatActivity.getCurrentAccount()).getCurrentTime() - message.messageOwner.date < chatActivity.getMessagesController().chatReadMarkExpirePeriod) && (ChatObject.isMegagroup(chatActivity.currentChat) || !ChatObject.isChannel(chatActivity.currentChat)) && chatActivity.chatInfo != null && chatActivity.chatInfo.participants_count <= chatActivity.getMessagesController().chatReadMarkSizeThreshold && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) && chatActivity.getChatMode() != ChatActivity.MODE_SAVED && message.canSetReaction() && !ChatObject.isMonoForum(chatActivity.currentChat);\n"
                "        final boolean showMessageAuthor = chatActivity.currentChat != null && !message.isOut() && ChatObject.isMonoForum(chatActivity.currentChat) && ChatObject.canManageMonoForum(chatActivity.getCurrentAccount(), chatActivity.currentChat) && -chatActivity.currentChat.linked_monoforum_id == message.getFromChatId();\n"
                "        final boolean showPrivateMessageSeen = !isReactionsViewAvailable && chatActivity.currentChat == null && chatActivity.currentEncryptedChat == null && (chatActivity.currentUser != null && !UserObject.isUserSelf(chatActivity.currentUser) && !UserObject.isReplyUser(chatActivity.currentUser) && !UserObject.isAnonymous(chatActivity.currentUser) && !chatActivity.currentUser.bot && !UserObject.isService(chatActivity.currentUser.id)) && (chatActivity.userInfo == null || !chatActivity.userInfo.read_dates_private) && !chatActivity.isInScheduleMode() && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (chatActivity.getConnectionsManager().getCurrentTime() - message.messageOwner.date < chatActivity.getMessagesController().pmReadDateExpirePeriod) && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);\n"
            )
            (ui / "TodoItemMenu.java").write_text(_menu_src, encoding="utf-8")
            (ui / "PollItemMenu.java").write_text(_menu_src, encoding="utf-8")

            (ui / "MessageSeenView.java").write_text(
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
                "        }));\n",
                encoding="utf-8",
            )
            (comps / "ReactedHeaderView.java").write_text(
                "            boolean showSeen = chat != null && message.isOutOwner() && message.isSent() && !message.isEditing() && !message.isSending() && !message.isSendError() && !message.isContentUnread() && !message.isUnread() && (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date < 7 * 86400)  && (ChatObject.isMegagroup(chat) || !ChatObject.isChannel(chat)) && chatInfo != null && chatInfo.participants_count <= MessagesController.getInstance(currentAccount).chatReadMarkSizeThreshold && !(message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest);\n"
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
                "            } else loadReactions();\n",
                encoding="utf-8",
            )
            (comps / "MessagePrivateSeenView.java").write_text(
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
                "        }));\n",
                encoding="utf-8",
            )
            (cells / "DialogCell.java").write_text(
                "                        } else if (isDialogCell) {\n"
                "                            drawCheck1 = (readOutboxMaxId > 0 && readOutboxMaxId >= message.getId()) || !message.isUnread() || ChatObject.isChannel(chat) && !chat.megagroup;\n"
                "                        } else {\n"
                "                            drawCheck1 = !message.isUnread() || ChatObject.isChannel(chat) && !chat.megagroup;\n"
                "                        }\n",
                encoding="utf-8",
            )
            (cells / "ChatMessageCell.java").write_text(
                "                } else if (currentMessageObject.isSent()) {\n"
                "                    if (!currentMessageObject.scheduled && !currentMessageObject.isUnread()) {\n"
                "                        drawCheck1 = true;\n"
                "                    } else {\n"
                "                        drawCheck1 = false;\n"
                "                    }\n"
                "                final boolean unread = currentMessageObject != null && currentMessageObject.isOut() && !currentMessageObject.scheduled && currentMessageObject.isUnread();\n"
                '                                sb.append(currentMessageObject.isUnread() ? getString("AccDescrMsgUnread", R.string.AccDescrMsgUnread) : getString("AccDescrMsgRead", R.string.AccDescrMsgRead));\n',
                encoding="utf-8",
            )
            (msg / "MessagesController.java").write_text(
                "    public ConcurrentHashMap<Long, Integer> dialogs_read_outbox_max = new ConcurrentHashMap<>(100, 1.0f, 2);\n"
                "    public ConcurrentHashMap<Long, Integer> dialogs_read_inbox_max = new ConcurrentHashMap<>(100, 1.0f, 2);\n"
                "        final boolean colgramGhostRead = org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId);\n"
                "        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);\n"
                "        boolean createReadTask;\n\n"
                "        if (threadId != 0) {\n"
                "            createReadTask = maxPositiveId != Integer.MAX_VALUE;\n"
                "        if (colgramGhostRead) {\n"
                "            // Ghost mode: skip the outgoing receipt entirely. Local read state above has\n"
                "            // already been updated, so the chat reads correctly on this device.\n"
                "            return;\n"
                "        }\n"
                "    public void markDialogAsUnread(long dialogId, TLRPC.InputPeer peer, long taskId) {\n"
                "        TLRPC.Dialog dialog = dialogs_dict.get(dialogId);\n"
                "                dialogs_read_inbox_max.put(dialogId, Math.max(value, update.max_id));\n"
                "            } else if (baseUpdate instanceof TL_update.TL_updateReadHistoryOutbox) {\n"
                "                stillUnreadMessagesCount.put(dialogId, update.still_unread_count);\n"
                "                dialogs_read_inbox_max.put(dialogId, Math.max(value, update.max_id));\n"
                '                FileLog.d("TL_updateReadChannelInbox");\n'
                "                new_dialogs_dict.put(d.id, d);\n\n"
                "                if (allowCheck && loadType == DIALOGS_LOAD_TYPE_CACHE && (d.read_outbox_max_id == 0 || d.read_inbox_max_id == 0) && d.top_message != 0) {\n"
                "                    if (currentDialog == null) {\n"
                "                        added = true;\n"
                "                        dialogs_dict.put(key, value);\n"
                "                    Integer value = dialogs_read_inbox_max.get(d.id);\n"
                "                    if (value == null) {\n"
                "                        value = 0;\n"
                "                    }\n"
                "                    dialogs_read_inbox_max.put(d.id, Math.max(value, d.read_inbox_max_id));\n\n"
                "                    value = dialogs_read_outbox_max.get(d.id);\n"
                "        getMessagesStorage().updateUnreadPollVotesCount(dialogId, topicId, 0);\n"
                "        TLRPC.TL_messages_readPollVotes req = new TLRPC.TL_messages_readPollVotes();\n"
                "                getConnectionsManager().sendRequest(req, (response, error) -> {\n"
                "                    if (error == null) {\n"
                "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
                "                        processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
                "                    }\n"
                "                });\n"
                "            getConnectionsManager().sendRequest(req, (response, error) -> {\n"
                "                if (error == null) {\n"
                "                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
                "                    processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
                "                }\n"
                "                if (newTaskId != 0) {\n"
                "            getConnectionsManager().sendRequest(req, (response, error) -> {\n"
                "                if (error == null) {\n"
                "                    if (response instanceof TLRPC.TL_messages_affectedMessages) {\n"
                "                        TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;\n"
                "                        processNewDifferenceParams(-1, res.pts, -1, res.pts_count);\n"
                "                    }\n"
                "                }\n"
                "            });\n",
                encoding="utf-8",
            )
            (msg / "MessagesStorage.java").write_text(
                "            if (!isEmpty(outbox)) {\n"
                "                for (int b = 0; b < outbox.size(); b++) {\n"
                "                    long key = outbox.keyAt(b);\n"
                "                    int messageId = outbox.get(key);\n"
                '                    database.executeFast(String.format(Locale.US, "UPDATE messages_v2 SET read_state = read_state | 1 WHERE uid = %d AND mid > 0 AND mid <= %d AND read_state IN(0,2) AND out = 1", key, messageId)).stepThis().dispose();\n'
                "                }\n"
                "            }\n"
                "                    state_dialogs.requery();\n"
                "                    state_dialogs.bindLong(1, dialog.id);\n"
                "                    state_dialogs.bindInteger(2, dialog instanceof TLRPC.TL_dialogCommunity ? dialog.last_message_date : messageDate);\n"
                "                    state_dialogs.bindInteger(3, dialog.unread_count);\n"
                "                if (info instanceof TLRPC.TL_channelFull) {\n"
                '                    cursor = database.queryFinalized("SELECT inbox_max, outbox_max FROM dialogs WHERE did = " + (-info.id));\n',
                encoding="utf-8",
            )

            def _apply_eol(path, old, new, label):
                txt = Path(path).read_text(encoding="utf-8")
                if new in txt:
                    return True
                self.assertIn(old, txt, f"Missing target for {label} in {Path(path).name}")
                Path(path).write_text(txt.replace(old, new, 1), encoding="utf-8")
                return True

            def _noop_patch(path, fn, marker, label):
                return True

            rsp.apply(str(repo), _noop_patch, _apply_eol)
            snap1 = {p.name: p.read_text(encoding="utf-8") for p in repo.rglob("*.java")}

            self.assertIn("ColgramReadReceiptsHelper.canShowReadReceipts(", snap1["ChatActivity.java"])
            self.assertIn("ColgramReadReceiptsHelper.canShowReadReceiptsForMessage(", snap1["PhotoViewer.java"])
            self.assertIn("ColgramReadReceiptsHelper.canShowReadReceipts(", snap1["TodoItemMenu.java"])
            self.assertIn("ColgramReadReceiptsHelper.canShowReadReceipts(", snap1["PollItemMenu.java"])
            self.assertIn("ColgramReadReceiptsHelper.loadGroupReadParticipants(", snap1["MessageSeenView.java"])
            self.assertIn("ColgramReadReceiptsHelper.loadGroupReadParticipants(", snap1["ReactedHeaderView.java"])
            self.assertIn("ColgramReadReceiptsHelper.loadPrivateReadDate(", snap1["MessagePrivateSeenView.java"])

            self.assertIn("public boolean isOutboxMessageRead(", snap1["MessagesController.java"])
            self.assertIn("isOutboxMessageRead(", snap1["DialogCell.java"])
            self.assertIn("isOutboxMessageRead(", snap1["ChatMessageCell.java"])
            self.assertIn("UPDATE dialogs SET outbox_max = MAX(outbox_max, %d)", snap1["MessagesStorage.java"])

            self.assertIn("ColgramLocalReadStore.recordLocalRead(", snap1["MessagesController.java"])
            self.assertIn("colgramClampDialogLocalRead(", snap1["MessagesController.java"])
            self.assertIn("ColgramLocalReadStore.clearDialog(", snap1["MessagesController.java"])
            self.assertIn("if (res.pts > 0)", snap1["MessagesController.java"])
            self.assertIn("ColgramLocalReadStore.getLocalReadMax(", snap1["MessagesStorage.java"])
            self.assertNotIn("dialogsNeedReload", snap1["MessagesController.java"])

            # Pass 2 strictly idempotent
            rsp.apply(str(repo), _noop_patch, _apply_eol)
            snap2 = {p.name: p.read_text(encoding="utf-8") for p in repo.rglob("*.java")}
            self.assertEqual(snap1, snap2)


if __name__ == "__main__":
    unittest.main()
