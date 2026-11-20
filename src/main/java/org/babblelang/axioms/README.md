# Babble's axioms

Everything the interpreter, the compiler, the analysis and the optimizer assume about specific Java or Babble functions and methods — without being able to check it — is stated here, and nowhere else ([BIP019](../../../../../../docs/design/bip019_refactoring-axioms.md)).

## Format

* **Java axioms** are stubs : a type annotated `@Axioms(Target.class)`, in `org.babblelang.axioms.<the target's package>`, named `<Target>Axioms` (a nested class takes its outer class's name : `Map.Entry` → `MapEntryAxioms`). Its methods mirror the target's, with exactly the same parameter types ; with `inherited = true` (the default), an override of a stub method in a subtype matches it too.
  * `@Profile` states a method's effect profile literally : its effects (`PURE`, `HERMETIC`, `DETERMINISTIC`, `NOTHROW`), whether it mutates or keeps its receiver, whether it borrows its parameters, whether it needs a call check ;
  * `@ShallowMut` : the mutator doesn't touch the objects its receiver holds ; `@View` : the result aliases the receiver ; `@FreshResult` : the result is a new object aliasing no argument ;
  * `@BytecodeSeed` : what the bytecode analyzer assumes about a method it reaches, instead of analysing it (natives notably) ;
  * `@Foldable` : the optimizer may treat a call as pure, `nothrow` and terminating, and call it at compile time ; `org.babblelang.effects.Idempotent` : it is idempotent ;
  * `@NonNull`, `@Nullable`, `@Parametric` : the nullness of a method's result (on the method) or of a parameter (on the parameter) ; `@Axioms(nullMarked = TARGET | SUBTYPES)` : unannotated type arguments in the target's signatures (or its subtypes') are non-null ;
  * `@Constructor` : the stub method mirrors a constructor ; `@MethodName("getClass")` : it mirrors a method a stub cannot declare under its own name ;
  * `@Axioms(allMethods = @Profile(…))` and `@Axioms(allConstructors = @Profile(…))` state the profile of every method, or constructor, of the target.
* **Matching :** the most specific stub that lists a method (or states `allMethods`) gives everything the axioms say about it — so a stub method carries all its annotations, effects and nullness together. An axiom given in a program (`axiom … on …`), an engine override or an annotation on the Java class itself wins over the effect axioms.
* **Generated once :** these stubs were generated from JDK 21's public `java.base` classes by encoding every rule the analysis used before BIP019, and checking, class by class, that the stubs give exactly the same answers. They are maintained by hand from then on. For classes outside `java.base`, the former rules only reach them through the stubs that are `inherited` (`Object`, `Comparable`, `Collection`, `Map`, `Map.Entry`, `Iterator`, `Comparator`, `BaseStream`).

## QUESTIONABLE

Axioms known to be doubtful, kept as they were so that BIP019's migration changes nothing ; each is tagged in its file, and fixing one is an edit of that file :

* **`equals`, `hashCode`, `compareTo` pure on every class** (`ObjectAxioms`, `ComparableAxioms`) : a Babble class may implement them with effects — to be fixed first (BIP019-D11) ;
* **collection methods by name on every implementation** (the collection stubs) : any `java.util.List` written by anyone is trusted, the mutators are assumed shallow although hashed and sorted collections call their elements' methods, and building any collection or `Iterator` (a `Scanner` reading a file included) counts as only allocating ;
* **`Arrays.asList` fresh** (`ArraysAxioms`) : it is a view of its array ;
* **the analyzer and the effect checker disagree** (`StringAxioms`) : the analyzer's seeds count `String.valueOf` as `nothrow`, the checker's profile does not.

## The files

* `java/io/` : `StringWriterAxioms.java`
* `java/lang/` : `BooleanAxioms.java`, `ByteAxioms.java`, `CharSequenceAxioms.java`, `CharacterAxioms.java`, `ClassAxioms.java`, `ComparableAxioms.java`, `DoubleAxioms.java`, `FloatAxioms.java`, `IntegerAxioms.java`, `IterableAxioms.java`, `LongAxioms.java`, `MathAxioms.java`, `ObjectAxioms.java`, `ProcessBuilderAxioms.java`, `RuntimeAxioms.java`, `ShortAxioms.java`, `StrictMathAxioms.java`, `StringAxioms.java`, `StringBufferAxioms.java`, `StringBuilderAxioms.java`, `SystemAxioms.java`, `ThreadAxioms.java`
* `java/lang/reflect/` : `ProxyAxioms.java`
* `java/math/` : `BigIntegerAxioms.java`
* `java/nio/` : `CharBufferAxioms.java`
* `java/nio/file/` : `DirectoryStreamAxioms.java`, `PathAxioms.java`
* `java/time/` : `LocalDateAxioms.java`
* `java/util/` : `AbstractListAxioms.java`, `AbstractMapSimpleEntryAxioms.java`, `AbstractMapSimpleImmutableEntryAxioms.java`, `AbstractQueueAxioms.java`, `AbstractSequentialListAxioms.java`, `ArrayDequeAxioms.java`, `ArrayListAxioms.java`, `ArraysAxioms.java`, `CollectionAxioms.java`, `CollectionsAxioms.java`, `ComparatorAxioms.java`, `DequeAxioms.java`, `EnumSetAxioms.java`, `HashtableAxioms.java`, `IteratorAxioms.java`, `LinkedHashMapAxioms.java`, `LinkedHashSetAxioms.java`, `LinkedListAxioms.java`, `ListAxioms.java`, `ListIteratorAxioms.java`, `MapAxioms.java`, `MapEntryAxioms.java`, `NavigableMapAxioms.java`, `NavigableSetAxioms.java`, `OptionalAxioms.java`, `PriorityQueueAxioms.java`, `PropertiesAxioms.java`, `QueueAxioms.java`, `ScannerAxioms.java`, `SequencedCollectionAxioms.java`, `SequencedMapAxioms.java`, `SequencedSetAxioms.java`, `ServiceLoaderAxioms.java`, `SetAxioms.java`, `SortedMapAxioms.java`, `SortedSetAxioms.java`, `StackAxioms.java`, `StringTokenizerAxioms.java`, `TreeMapAxioms.java`, `TreeSetAxioms.java`, `VectorAxioms.java`
* `java/util/concurrent/` : `ArrayBlockingQueueAxioms.java`, `BlockingDequeAxioms.java`, `BlockingQueueAxioms.java`, `ConcurrentHashMapAxioms.java`, `ConcurrentLinkedDequeAxioms.java`, `ConcurrentLinkedQueueAxioms.java`, `ConcurrentNavigableMapAxioms.java`, `ConcurrentSkipListMapAxioms.java`, `ConcurrentSkipListSetAxioms.java`, `CopyOnWriteArrayListAxioms.java`, `DelayQueueAxioms.java`, `LinkedBlockingDequeAxioms.java`, `LinkedBlockingQueueAxioms.java`, `LinkedTransferQueueAxioms.java`, `PriorityBlockingQueueAxioms.java`, `SynchronousQueueAxioms.java`
* `java/util/function/` : `BiConsumerAxioms.java`, `BiFunctionAxioms.java`, `BiPredicateAxioms.java`, `BinaryOperatorAxioms.java`, `BooleanSupplierAxioms.java`, `ConsumerAxioms.java`, `DoubleBinaryOperatorAxioms.java`, `DoubleConsumerAxioms.java`, `DoubleFunctionAxioms.java`, `DoublePredicateAxioms.java`, `DoubleSupplierAxioms.java`, `DoubleToIntFunctionAxioms.java`, `DoubleToLongFunctionAxioms.java`, `DoubleUnaryOperatorAxioms.java`, `FunctionAxioms.java`, `IntBinaryOperatorAxioms.java`, `IntConsumerAxioms.java`, `IntFunctionAxioms.java`, `IntPredicateAxioms.java`, `IntSupplierAxioms.java`, `IntToDoubleFunctionAxioms.java`, `IntToLongFunctionAxioms.java`, `IntUnaryOperatorAxioms.java`, `LongBinaryOperatorAxioms.java`, `LongConsumerAxioms.java`, `LongFunctionAxioms.java`, `LongPredicateAxioms.java`, `LongSupplierAxioms.java`, `LongToDoubleFunctionAxioms.java`, `LongToIntFunctionAxioms.java`, `LongUnaryOperatorAxioms.java`, `ObjDoubleConsumerAxioms.java`, `ObjIntConsumerAxioms.java`, `ObjLongConsumerAxioms.java`, `PredicateAxioms.java`, `SupplierAxioms.java`, `ToDoubleBiFunctionAxioms.java`, `ToDoubleFunctionAxioms.java`, `ToIntBiFunctionAxioms.java`, `ToIntFunctionAxioms.java`, `ToLongBiFunctionAxioms.java`, `ToLongFunctionAxioms.java`, `UnaryOperatorAxioms.java`
* `java/util/regex/` : `PatternAxioms.java`
* `java/util/stream/` : `BaseStreamAxioms.java`, `DoubleStreamAxioms.java`, `IntStreamAxioms.java`, `LongStreamAxioms.java`, `StreamAxioms.java`, `StreamSupportAxioms.java`
