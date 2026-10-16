# Android resource lint follow-up

This bounded pass changes resource metadata, redundant resource variants and
typography. It preserves every locale, every base resource name and all format
argument signatures. No natural-language translations, lint suppressions or
baseline entries are added.

## Starting report and expected delta

The archived debug reports contain the following counts. The expected counts
below describe this resource patch alone; they are not a new Gradle lint result.
Concurrent source changes may produce additional deltas.

| Module | Starting errors / warnings / hints | Expected after this resource patch |
|---|---:|---:|
| App | 244 / 294 / 19 | 227 / 239 / 19 |
| Wear | 0 / 47 / 10 | 0 / 45 / 10 |

App errors start at 241 `MissingTranslation` and three `MissingQuantity`.
Seventeen nonlinguistic constants account for the expected error reduction.
The expected warning reductions are 34 `TypographyEllipsis`, 18 `UnusedQuantity` and
three resource `ObsoleteSdkInt` in App, plus two resource
`ObsoleteSdkInt` in Wear. Other diagnostics remain open.

## Changes and checks

- Mark 17 symbol-only resources as nontranslatable: timestamp separators,
  mention/channel symbols, progress glyphs and six attachment-type emoji.
  Remove their 273 byte-identical localized overrides, retaining the unchanged
  default values. Natural-language status text and locale-specific punctuation
  remain translatable.
- Remove 18 unreachable `one` plural items in Chinese, Indonesian, Japanese,
  Korean, Thai and Vietnamese resource variants. All 201 surviving plural items
  are unchanged. These languages use only cardinal `other` in the
  [CLDR language rules](https://unicode.org/cldr/charts/48/supplemental/language_plural_rules.html).
- Use the single ellipsis character in 34 reported UI status or placeholder
  strings. Only the three dots change, following the
  [TypographyEllipsis check](https://googlesamples.github.io/android-custom-lint-rules/checks/TypographyEllipsis.md.html).
- Keep the original `values-he` and `values-id` folders and the matching modern
  BCP 47 tags in `locales_config.xml`. Lint32.3.1's `LocaleFolder` check asks for
  `iw/in`, but its `UnusedTranslation` check then rejects those aliases against
  the modern configuration. Inspection of the installed detector also confirms
  that `values-b+he/id` would still trigger `LocaleFolder`. Keep the two inherited
  warnings instead of duplicating app-language entries or adding a suppression.
  Android15's [Locale changes](https://developer.android.com/about/versions/15/behavior-changes-15#openjdk-api-changes)
  also use the modern Hebrew/Indonesian codes.
- Move App and Wear adaptive launcher XML from `mipmap-anydpi-v26` to
  `mipmap-anydpi`, preserving the files byte-for-byte. App requires API 26 and
  Wear API 33. Move the unchanged Wear API 31 style to `values/styles.xml`.
  Remove the obsolete API 23 marker from the two App status-bar theme items;
  their attribute values stay unchanged.

Static verification parses all 86 App/Wear XML resource files, checks every
changed string against the original snapshot and checks the format signatures.
It confirms that only the declared identical overrides and unreachable plural
items were removed, renamed icon/style contents are unchanged, the Arabic file
is unchanged and `git diff --check` passes. There are no new JVM test cases.
The combined source now compiles and its full tests pass; see the measured results below.
The local ignored evidence is under
`.validation/artifacts/android-lint-resources/`.

## Arabic review still required

The three Arabic `MissingQuantity` errors remain visible in
`values-ar/strings.xml`: `notification_and_more`, `people_count` and
`location_notes_title`. Each lacks `zero`, `two`, `few` and `many`.
Arabic has all six cardinal categories, but category membership does not provide
grammatically correct phrases; the existing generic forms also need review.
A fluent Arabic reviewer must supply the inflections and agreement, including
the dual and zero forms, before these resources can be completed safely.
See [CLDR plural rules](https://cldr.unicode.org/index/cldr-spec/plural-rules)
and the [Arabic category table](https://unicode.org/cldr/charts/48/supplemental/language_plural_rules.html#ar).

The production call sites already supply the display counts separately from
the plural selector, as described by the
[Android plural resource API](https://developer.android.com/guide/topics/resources/string-resource#Plurals):
notifications pass `(extra, extra)`, people counts pass `(count, count)`, and
location-note titles pass `(count, geohash, count)`. Unknown people counts use
the separate unknown string, so natural zero/dual forms need not imitate a
numeric placeholder. No call-site change is part of this resource patch.

The remaining 224 expected `MissingTranslation` errors require actual language
work. Existing prose, locale-specific punctuation and human-readable labels
are not reclassified to hide that work.

## Combined-source verification

The final combined App/Wear debug build and unfiltered unit-test execution pass
with JDK21 offline. App collects885 cases:882 pass and three inherited skips;
Wear passes354, with no failures or errors. Six new App cases cover the real
palette contrast and rejection of unrelated boot intents. The132/13 targeted
cases were an earlier intermediate pass, not an additional final collection.

| XML report | Errors | Warnings | Hints |
|---|---:|---:|---:|
| App before |244|294|19|
| App final |227|229|19|
| Wear before |0|47|10|
| Wear final |0|43|10|

There are no added diagnostic identities and no `MissingPermission`. The App
lint analysis executes after the final source changes. Wear reuses its result
from the successful targeted build because no Wear or shared input changes
afterward; its complete unit tests execute again without filters.

The final667 build inputs match before/after the full run, and the two changed
shared Wear files match their App owners. Generated shared trees are not edited.
The resource-only estimates above remain estimates; the measured totals also
include explicit locale formatting, API guards, named scan constants and the
boot-action guard. An intermediate lint run exposed two `NonObservableLocale`
errors and a `ConfigurationScreenWidthHeight` warning; the final source uses
observable Compose locale and actual window bounds and the diagnostics are gone.

Lint is still not clean. The inherited `abortOnError=false` and existing baseline
remain unchanged.224 missing-translation diagnostics, three Arabic plural errors
and the two locale-folder compatibility warnings remain visible. Composition
semantics, reflow and three contrast tests do not establish GUI rendering,
TalkBack order, activation or speech. Those need direct assistive-technology
checks; no physical radio or delivery qualification is claimed.
