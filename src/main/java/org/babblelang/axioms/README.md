# Babble's axioms

Everything the interpreter, the compiler, the analysis and the optimizer assume about specific Java or Babble functions and methods — without being able to check it — is stated here, and nowhere else ([BIP019](../../../../../../docs/design/bip019_refactoring-axioms.md)).

## Format

* **Java axioms** are stubs : a type annotated `@Axioms(Target.class)`, in `org.babblelang.axioms.<the target's package>`, named `<Target>Axioms` (a nested class takes its outer class's name : `Map.Entry` → `MapEntryAxioms`). Its methods mirror the target's, with exactly the same parameter types, and an override of a stub method in a subtype matches it too (unless `inherited = false`).
  * `@Profile` states a method's effect profile literally : its effects (`PURE`, `HERMETIC`, `DETERMINISTIC`, `NOTHROW`), whether it mutates or keeps its receiver, whether it borrows its parameters, whether it needs a call check ;
  * `@ShallowMut` : the mutator doesn't touch the objects its receiver holds ; `@View` : the result aliases the receiver ; `@FreshResult` : the result is a new object aliasing no argument ;
  * `@Axioms(allMethods = @Profile(…))` and `@Axioms(allConstructors = @Profile(…))` state the profile of every method, or constructor, of the target.
* The most specific stub that says something about a method wins ; an axiom given in a program (`axiom … on …`), an engine override or an annotation on the Java class itself wins over them all.

## The files

| File | What it assumes |
|---|---|
| `java/lang/ObjectAxioms.java` | `equals`, `hashCode` and `toString` pure on every class — QUESTIONABLE |
| `java/lang/ComparableAxioms.java` | `compareTo` pure on every class — QUESTIONABLE |
| `java/lang/StringAxioms.java` | every method and constructor of `String` pure, some `nothrow` |
| `java/lang/IterableAxioms.java` | `Iterable` itself (not its subtypes) : `iterator()` a view — QUESTIONABLE |
| `java/util/MapEntryAxioms.java` | the accessors of a map entry |
| `java/util/AbstractMapSimpleEntryAxioms.java`, `java/util/AbstractMapSimpleImmutableEntryAxioms.java` | building an entry only allocates |
| `java/util/ArraysAxioms.java` | `Arrays.asList` returns a fresh list — QUESTIONABLE : it is a view of its array |
| `java/util/CollectionsAxioms.java` | `Collections.nCopies`, `singleton…`, `empty…` return fresh collections |
| `java/util/CollectionAxioms.java`, `ListAxioms.java`, `SetAxioms.java`, `SortedSetAxioms.java`, `NavigableSetAxioms.java`, `QueueAxioms.java`, `SequencedCollectionAxioms.java`, `MapAxioms.java`, `SortedMapAxioms.java`, `NavigableMapAxioms.java`, `SequencedMapAxioms.java`, `IteratorAxioms.java`, `ListIteratorAxioms.java` | the read, view and mutating methods of the collection interfaces ; building any collection only allocates ; `List.of`, `Set.of`, `Map.of` and their `copyOf` return fresh collections — QUESTIONABLE : by name, for every implementation, and the mutators are assumed shallow although hashed and sorted collections call their elements' methods |
| `java/util/HashtableAxioms.java`, `PriorityQueueAxioms.java`, `ScannerAxioms.java`, `StackAxioms.java`, `VectorAxioms.java`, `java/util/concurrent/BlockingDequeAxioms.java`, `BlockingQueueAxioms.java`, `ConcurrentHashMapAxioms.java`, `CopyOnWriteArrayListAxioms.java`, `PriorityBlockingQueueAxioms.java` | the same, for methods these classes declare beyond the interfaces' — QUESTIONABLE, as above (`Scanner` is an `Iterator`, so its `hasNext(String)` counts as a read) |

The collection stubs were generated once from JDK 21's classes, reproducing the name lists the effect checker used before BIP019, overload by overload ; they are maintained by hand from then on.

## QUESTIONABLE

Axioms known to be doubtful, kept as they were so that BIP019's migration changes nothing ; each is tagged in its file, and fixing one is an edit of that file :

* **`equals`, `hashCode`, `compareTo` pure on every class** (`ObjectAxioms`, `ComparableAxioms`) : a Babble class may implement them with effects — to be fixed first (BIP019-D11) ;
* **collection methods by name on every implementation** (the collection stubs) : any `java.util.List` written by anyone is trusted, and building any collection or `Iterator` (a `Scanner` reading a file included) counts as only allocating ;
* **`Arrays.asList` fresh** (`ArraysAxioms`) : it is a view of its array.
