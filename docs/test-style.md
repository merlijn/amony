# Writing tests

## Backend (Scala)

Tests should be written in Scala using the [ScalaTest](https://www.scalatest.org/) framework.

Each test file should have a similarly named implementation file, with the exception of shared test utilities.

### Test style

Prefer the following test style.

```scala
"classname/container" when {
  "context/function/endpoint A" should {
    // setup context here
    
    "specific test 1" in {
      // test code here
    }
    "specific test 2" in {
      // test code here
    }
  }
  "context/function/endpoint B" should {
    // setup context here
    // etc...
  }
}
```

If there are really no multiple contexts, you can also use a single `should` block:

```scala
"classname/container" should {
  "specific test 1" in {
    // test code here
  }
  "specific test 2" in {
    // test code here
  }
}
```