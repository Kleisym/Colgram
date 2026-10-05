# Search tab counters

## What was there

The tab strip was already wide and already split - Chats, Channels, Apps (bots), Posts, five message
filters, Downloads and Global Search, eleven tabs in total. What each tab did not carry was its own
size.

    ViewPagerAdapter.getItemTitle()
        DIALOGS_TYPE      -> getString(R.string.ChatsTab)
        CHANNELS_TYPE     -> getString(R.string.ChannelsTab)
        BOTS_TYPE         -> getString(R.string.AppsTab)
        GLOBAL_SEARCH     -> getString(R.string.GlobalSearch)

So a search screen listed eleven categories and, for each one, the only way to learn whether anything
was found was to open it. On a screen whose whole purpose is narrowing something down, that is the
one thing the layout could have said and did not.

## The change

    private CharSequence withCount(CharSequence title, RecyclerListView.Adapter<?> adapter)

The number is the adapter's own getItemCount(), so it is exactly what the tab will show - not an
estimate, and not a separate counter that can drift from the list it describes.

Omitted when a tab is empty. That is deliberate: "Chats 0" reads as an error and as a claim about the
world, whereas "Chats" reads as a tab that has not been searched yet. A zero on an empty search is
also indistinguishable from a counter that failed to load, and hiding it means the two cannot be
mistaken for each other.

## What was checked before changing it

The screenshot from the report showed tabs already carrying numbers, which suggested the work was
done. It is not - and the thing that produced those numbers is worth naming, because it looks like
the feature and is not:

    SearchCounterView.java     313 lines, referenced by nothing in the tree

A dead component. Dead code that reads as "this exists" is why the gap survived: the class that
would have drawn the counters was present, unused, and named exactly after the job.

## Verified

    :TMessagesProj:compileStandaloneJavaWithJavac   BUILD SUCCESSFUL in 8m 47s

## Дефект, найденный при проверке: счётчики не обновлялись при вводе

Проверка по коду, а не по наличию класса, показала вторую неработающую часть.

Счётчики живут в `getItemTitle`, который читает `getItemCount()` адаптера, то есть они свежие
ровно настолько, насколько недавно перерисовывались заголовки. А перерисовывались они не при
вводе:

```java
private void updateGlobalSearchTab() {
    globalSearchResultsAdapter.notifyDataSetChanged();   // данные обновились
    if (globalSearchEmptyView != null && dialogsSearchAdapter != null) {
        ...                                              // пустое состояние обновилось
    }
    // заголовки вкладок - нет
}
```

`updateTabs()` существует, но вызывался только из `collapsePublicPosts()` - то есть при сворачивании
публичных постов, а не при поиске. Набранный запрос менял списки, а числа на полосе вкладок
оставались от предыдущего поиска.

Счётчик, отстающий от нажатия клавиши, хуже отсутствующего: его читают как актуальный.

## Почему нельзя было просто вызвать fillTabs

`fillTabs()` сносит полосу вкладок и собирает заново. Это правильно, когда меняется **состав**
вкладок - публичные посты разворачиваются и сворачиваются по ходу поиска, - но не когда меняются
только подписи. Пересборка на каждое нажатие:

- пересобирает идентификаторы и текущую позицию, пока полоса измеряется, то есть вкладка
  выпрыгивает из-под пальца;
- запускает переход каждую букву.

Поэтому добавлено точечное обновление подписей без пересборки:

```
ViewPagerFixed.TabsView.updateTitlesFrom(Adapter)
    - идёт по уже существующим вкладкам
    - спрашивает у адаптера, что каждая должна говорить сейчас
    - перезаписывает только изменившиеся
    - перемеряет ширину изменившихся: заголовок, получивший " 12", шире прежнего
    - правит allTabsWidth, чтобы полоса прокручивалась по фактической ширине

ViewPagerFixed.updateTabTitles()  ->  tabsView.updateTitlesFrom(adapter)
SearchViewPager.updateGlobalSearchTab()  ->  refreshTabTitles()
```

Метод живёт внутри `TabsView`, потому что `tabs`, `idToPosition` и `positionToWidth` - его
собственное состояние; снаружи пришлось бы дублировать подсчёт ширины, который делает `addTab`.

Одна деталь API: `positionToWidth` - это `SparseIntArray`, и у него нет `containsKey`. Первая
версия вызывала его и не собралась:

```
symbol: method containsKey(int)
location: variable positionToWidth of type SparseIntArray
```

`get(i, 0)` даёт то же самое: у ненайденного ключа ширины нет, а значит и прежней ширины нет.

```
:TMessagesProj:compileStandaloneJavaWithJavac
BUILD SUCCESSFUL in 1m 43s
```
