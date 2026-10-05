# Возврат из чата с восстановлением запроса

Дата: 2026-10-01.

## Что было

`SearchViewPager.onResume()` выглядел достаточным:

```java
public void onResume() {
    if (dialogsSearchAdapter != null) {
        dialogsSearchAdapter.notifyDataSetChanged();
    }
}
```

Но он обновлял только список чатов. Путь возврата такой: из результата глобального поиска
открывается чат, экран уходит в стек, пользователь жмёт «назад» - и возвращается к тому же поиску.
Ничего в этом пути не перезапускало поиск заново, поэтому списки результатов оставались такими,
какими были в момент нажатия:

- пустыми, если адаптер не успел отдать данные до перехода;
- либо результатами по запросу, которого в поле уже нет.

`lastSearchString` при этом сохранялся корректно - не терялся запрос, а терялся **результат** по
нему. Пользователь возвращается и видит пустоту там, где только что что-то было.

## Что изменено

```java
public void onResume() {
    if (dialogsSearchAdapter != null) {
        dialogsSearchAdapter.notifyDataSetChanged();
    }
    if (!TextUtils.isEmpty(lastSearchString) && searchListView != null) {
        search(searchListView, getCurrentPosition(), lastSearchString, true);
    }
}
```

`search()` - единственное место, которое перечитывает поле и прогоняет все адаптеры, то есть ровно
то, что нужно при возврате из чата, и не то, что делает `notifyDataSetChanged`.

Проверка на непустой запрос не декоративная: без неё возврат из **любого** чата очищал бы список
недавних поисков, который показывается при пустом поле.

Путь вызова проверен, а не предположен:

```
DialogsActivity.onResume():7117   searchViewPager.onResume();
```

```
:TMessagesProj:compileStandaloneJavaWithJavac
BUILD SUCCESSFUL in 2m 7s
```

## Чего этот фикс не делает

## Позиция прокрутки — сделано следом

Первая версия фикса возвращала запрос, но не позицию. `notifyDataSetChanged` в адаптере прокручивает
список вверх, и при возврате человек оказывался на первом результате:

```
notifyDataSetChanged()
    super.notifyDataSetChanged();
    if (!lastSearchScrolledToTop && searchListView != null) {
        searchListView.scrollToPosition(0);      // <- всегда вверх
        lastSearchScrolledToTop = true;
    }
```

Для нового запроса прыжок вверх правильный. Для возврата из чата - нет: прокрутивший до
сорокового результата, открыв его и вернувшись по «назад», человек оказывался на пустом верху и
читал это как «поиск потерял мои результаты».

```java
int resume = searchLayoutManager != null ? searchLayoutManager.findFirstVisibleItemPosition() : 0;
if (resume < 0) resume = 0;

search(searchListView, getCurrentPosition(), lastSearchString, true);

final int position = Math.max(0, resume);
if (position > 0) {
    // post(), потому что поиск выше только ставит выборку в очередь, и прокручивать
    // некуда, пока она не вернётся
    searchListView.post(() -> { ... scrollToPositionWithOffset(position, 0); });
}
```

Две детали, обе ломают иначе:

- `findFirstVisibleItemPosition` есть у `LayoutManager`, а не у `RecyclerListView`. Первая версия
  вызвала его на списке и не собралась.
- `LayoutManager.findFirstVisibleItemPosition()` возвращает `NO_POSITION`, то есть `-1`, когда
  списка ещё нет. Это значение нельзя отдавать прокрутке, поэтому отрицательное заменяется нулём.

```
:TMessagesProj:compileStandaloneJavaWithJavac
BUILD SUCCESSFUL in 2m 43s
```
