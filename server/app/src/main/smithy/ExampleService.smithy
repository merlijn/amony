namespace nl.amony.app.api

use alloy#simpleRestJson

@simpleRestJson
service HelloWorldService {
  version: "1.0.0",
  operations: [Hello]
}

@http(method: "POST", uri: "/smithy/hello/{name}", code: 200)
operation Hello {
  input: Person,
  output: Greeting,
  errors: [MyError, UnauthorizedUser]
}

@error("client")
@httpError(404)
structure MyError { }

@error("client")
@httpError(401)
structure UnauthorizedUser { }

structure Person {
  @httpLabel
  @required
  name: String,

  @httpQuery("town")
  town: String
}

structure Greeting {
  @required
  message: String
}