# Search input history, with per-entry deletion

## What was there

Nothing. FragmentSearchField had no history support of any kind - no storage, no list, no
forgetting - and DialogsActivity, the screen the global search lives on, had nothing to hook into.

## What was added

    TMessagesProj/.../Components/ColgramSearchHistory.java     storage plus the popup
    DialogsActivity.onTextChanged                              records the query, shows the popup
    DialogsActivity.showSearch()                               removes the popup when search closes

Storage is app-private preferences under "colgram_search_history", newest first, de-duplicated and
capped at 30, with a 2-character floor so a keystroke is not a search.

Two decisions worth stating, because they are the ones that are easy to get wrong:

-   **Recorded on typing, not on closing the search.** Closing can happen in several ways - the close
    button, a back gesture, a result being opened, the fragment being destroyed - and each would
    need the same call, and one of them would eventually be missed.
-   **Shown when the field is empty.** With text in the field there are results to look at, and a
    floating list over them would take the taps meant for the results. With an empty field there is
    nothing else to show, which is also where somebody looks for this.

## Deletion

Each row has its own ✕, which removes that one query from storage AND from the already-open list, so
a deleted row does not come back on the next redraw. A "Очистить историю" row clears everything, so
the feature is escapable rather than being a privacy surface with no exit.

## Verified

    :TMessagesProj:compileStandaloneJavaWithJavac   BUILD SUCCESSFUL in 1m 36s

## Дефект, найденный при проверке: история писалась на каждое нажатие

Проверка по коду показала, что пункт был закрыт только формально. `remember()` вызывался из
`onTextChanged`, а этот колбэк срабатывает на **каждое нажатие клавиши**:

```java
public void onTextChanged(EditText editText) {
    String text = editText.getText().toString();
    if (text.trim().length() >= 2) {
        ColgramSearchHistory.remember(text);
    }
```

С порогом `MIN_LENGTH = 2` набор «анна» оставлял четыре записи: «ан», «анн», «анна», «анна».
История превращалась в лог всех промежуточных префиксов.

Комментарий над `remember()` утверждал обратное — «вызывается при закрытии поиска, а не на каждом
нажатии», — и именно это расхождение между намерением и местом вызова позволяло дефекту пережить
проверку.

Исправлено:

```java
// closeSearchField(boolean), читается ДО очистки поля
if (fragmentSearchField != null && fragmentSearchField.editText != null) {
    ColgramSearchHistory.remember(fragmentSearchField.editText.getText().toString());
}
fragmentSearchField.editText.getText().clear();
```

Порядок важен: ниже по этому же методу поле очищается, и после этого записывать нечего. Вызов из
`onTextChanged` убран, остался ровно один.

```
:TMessagesProj:compileStandaloneJavaWithJavac
BUILD SUCCESSFUL in 2m 34s
```

Three compile errors had to be fixed first and are worth recording, because each one is a trap in
this file rather than a logic mistake:

-   DialogsActivity is a BaseFragment, not an Activity - so it has no findViewById and no getWindow()
-   and it is not a Context - so a View constructor cannot take it directly
-   the popup is attached to the activity's decor view, which is what lets it float over the search
    results at all
