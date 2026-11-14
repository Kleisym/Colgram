"""Pinned, component-level theme fixes; no global override of other theme keys."""

STORY_HINT_MARKER = "// COLGRAM_STORY_HINT_PALETTE = 1"


def _replace_once(source, anchor, replacement, detail):
    if source.count(anchor) != 1:
        raise ValueError(f"Missing or ambiguous story hint anchor: {detail}")
    return source.replace(anchor, replacement, 1)


def patch_story_hints(source):
    if STORY_HINT_MARKER in source:
        return source

    regular_init = "                storyHint = new HintView2(context, HintView2.DIRECTION_RIGHT)"
    source = _replace_once(
        source, regular_init,
        "                final org.colgram.core.ColgramThemePolicy.HintPalette hintColors = colgramStoryHintColors();\n"
        + regular_init, "regular constructor",
    )
    source = _replace_once(
        source,
        "                        .setBgColor(getThemedColor(Theme.key_undo_background))\n"
        "                        .setOnHiddenListener(",
        "                        .setBgColor(hintColors.background)\n"
        "                        .setTextColor(hintColors.text)\n"
        "                        .setOnHiddenListener(",
        "regular paints",
    )
    # Replace the stock link helper with its explicit-color variant. Stock type 0
    # sets a global link color rather than the requested undo key.
    source = _replace_once(
        source,
        '            Spannable text = AndroidUtilities.replaceSingleTag(LocaleController.getString("StoriesPremiumHint2")'
        ".replace('\\n', ' '), Theme.key_undo_cancelColor, 0, () -> {",
        "            final org.colgram.core.ColgramThemePolicy.HintPalette hintColors = colgramStoryHintColors();\n"
        '            Spannable text = AndroidUtilities.replaceSingleLinkBold(LocaleController.getString("StoriesPremiumHint2")'
        ".replace('\\n', ' '), hintColors.link, () -> {",
        "premium text",
    )
    source = _replace_once(
        source,
        "                .setBgColor(getThemedColor(Theme.key_undo_background));",
        "                .setBgColor(hintColors.background)\n"
        "                .setTextColor(hintColors.text);",
        "premium paints",
    )

    show = "            storyHint.show();"
    for indent in ("            ", "                "):
        source = _replace_once(
            source,
            "\n" + indent + show.strip(),
            "\n" + indent + "showColgramStoryHint();",
            f"regular show ({len(indent)} spaces)",
        )
    method = "    private void updateStoriesPosting() {"
    return _replace_once(
        source, method,
        f"""    {STORY_HINT_MARKER}
    private org.colgram.core.ColgramThemePolicy.HintPalette colgramStoryHintColors() {{
        return org.colgram.core.ColgramThemePolicy.forStoryHint(
                getThemedColor(Theme.key_windowBackgroundWhite),
                getThemedColor(Theme.key_undo_background),
                getThemedColor(Theme.key_undo_cancelColor));
    }}

    private void showColgramStoryHint() {{
        final org.colgram.core.ColgramThemePolicy.HintPalette palette = colgramStoryHintColors();
        storyHint.setBgColor(palette.background).setTextColor(palette.text).show();
    }}

{method}""", "palette helper",
    )
