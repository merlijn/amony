# Writing code

## Flatmap, for comprehensions and EitherT

Prefer "for comprehension" chains when the amount of nesting exceeds 1, if possible 

Bad
```
a.flatMap: b =>
  b.flatMap: c =>
    c.flatMap: d => 
      // and so on ...
```
Good
```
for {
  a <- ...
  b <- ...
  c <- ...
} yield ...
```

Avoid these types of nested matching constructions
```
a match 
  case None => IO.pure(Left(x))
  case Some(b) => b.something match {
    case Left(x) => IO.pure(Left(x)
    case Right(c) => c.something match {
       and so on
    }
  }
```

Try to align them in a consolidated for comprehension, if possible. There are cases where it is unavoidable, use your best judgement.

Prefer `EitherT` to consolidate these chains of compatible types like
- `Option[T]`
- `Either[A, B]`
- `IO[T]`
- `IO[Option[T]`
- `IO[Either[A, B]`
- and so on

All these (and more) have EitherT constructors. Sometimes OptionT or another monad transformer may fit better, again, use your best judgement.

It's OK to return EitherT from functions, including those defined by traits. 