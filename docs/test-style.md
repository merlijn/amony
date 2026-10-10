# Writing tests

## Backend (Scala)

Tests should be written in Scala using the [ScalaTest](https://www.scalatest.org/) framework.

Each test file should have a similarly named implementation file, with the exception of shared test utilities.

### Test style

Prefer the following test style. `ImplementationFileName` refers to the name of the implementation file being tested.

```scala
class ImplementationFileNameSpec extends AnyWordSpecLike {

  "classname/utility A" when {
    "context/function/endpoint" should {
      // setup context here
      
      "specific test 1" in {
        // test code here
      }
      "specific test 2" in {
        // test code here
      }
    }
    "context/function/endpoint" should {
      // setup context here
      // etc...
    }
  }
  
  // Implementation files may contain multiple classes or utilities
  "classname/utility B" when {
    // ...
  }
}
```

If there are really no multiple contexts, you can also use a single `should` block:

```scala
class ImplementationFileNameSpec extends AnyWordSpecLike {

  "classname/utility" should {
    "specific test 1" in {
      // test code here
    }
    "specific test 2" in {
      // test code here
    }
  }
}
```