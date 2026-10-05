# Why channels showed no subscriber count in global search

## The cause, located exactly

DialogCell does contain the code that draws a channel's subscriber count. It was simply unreachable
from search, because the branch was guarded by a dialogs type that search never sets:

    DialogCell.java:1718
    } else if (dialogsType == DialogsActivity.DIALOGS_TYPE_ADD_USERS_TO) {
        ...
        messageString = LocaleController.formatPluralStringComma("Subscribers", chat.participants_count);

DIALOGS_TYPE_ADD_USERS_TO is the "add people to a chat" picker. Global search rows fall through every
branch and land on the last-message string instead, which for a search result is either empty or a
stale snippet - so a person searching for a channel had nothing to judge it by.

ProfileSearchCell was never the problem: it has drawn "Subscribers" since before this change
(ProfileSearchCell.java:680). That cell is used for contacts and users, not for channel rows in the
chat list.

## The fix

A new branch in DialogCell, after ADD_USERS_TO:

    } else if (colgramGlobalSearch && chat != null && ChatObject.isChannel(chat)) {
        messageString = chat.megagroup
                ? "Members, N"
                : "Subscribers, N";
        drawCount2 = false;
    }

and a flag to make the two cases distinguishable, because the same cell type serves both lists:

    DialogCell.setColgramGlobalSearch(boolean)     fluent, so the anonymous subclass in the adapter
                                                  can keep its isForumCell() override
    DialogsSearchAdapter.allowGlobalSearch         final, set from the constructor parameter that was
                                                  already there and only used for search results

Scoped to search results on purpose. In a chat list the same cell shows the last message, which is
the more useful line there; changing that would trade one wrong screen for another.

## The second defect, which would have made the first one do nothing

The branch reads the cell's chat field, and that field is never assigned anywhere in the file:

    DialogCell.java:509    private TLRPC.Chat chat;
    DialogCell.java:3606   TLRPC.Chat chat = this.chat;

That second line is the only read. Nothing writes. So the first version of this change compiled, would
have looked right, and would have changed nothing on screen - the same class of bug as the branch that
was unreachable, one level deeper.

The chat is now loaded at the top of update(), next to the other per-row state:

    chat = currentDialogId < 0 && !DialogObject.isEncryptedDialog(currentDialogId)
            ? MessagesController.getInstance(currentAccount).getChat(-currentDialogId)
            : null;

One map read, on a path that already does several, and only for rows that can be channels.

## Verified

    :TMessagesProj:compileStandaloneJavaWithJavac   BUILD SUCCESSFUL in 3m 59s

## What is not fixed yet, from the same list

-   wider counters on the channel / chats / bots filter tabs - the count strip on the search screen
    comes from a different view, and SearchCounterView turned out to be dead code, referenced by
    nothing in the tree
-   opening a channel from a search result and coming back to the list with the query intact
-   input history in the global search field, with per-entry deletion
-   the rest of the list: WARP toggle behaviour, call proxy defaults, .plugin import, built-in
    plugins becoming features, the Telegram rename, the crashes
