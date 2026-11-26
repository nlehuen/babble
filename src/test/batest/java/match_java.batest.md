# Pattern matching on Java enums (`BIP038`)

References : `BIP038` §4.2, §7.4 (AOT-resilient Java `enum` switch-map dispatch, OR patterns, and `else`).

## Java enum switch-map dispatch and OR patterns

```babble
def D = java.time.DayOfWeek
def label = fn (d: java.time.DayOfWeek): string -> (
    match d
    case D.SATURDAY | D.SUNDAY then ("wknd")
    case D.MONDAY then ("mon")
    case D.TUESDAY then ("tue")
    case D.WEDNESDAY then ("wed")
    else ("other")
)
assert(label(D.MONDAY) == "mon")
assert(label(D.FRIDAY) == "other")
assert(label(D.SUNDAY) == "wknd")
```

## Nullable Java enum matching null and constants

```babble
def D = java.time.DayOfWeek
def name = fn (d: java.time.DayOfWeek?): string -> (
    match d
    case null then ("none")
    case D.MONDAY then ("mon")
    case D.TUESDAY then ("tue")
    case D.WEDNESDAY then ("wed")
    else ("rest")
)
assert(name(null) == "none")
assert(name(D.MONDAY) == "mon")
assert(name(D.FRIDAY) == "rest")
```
