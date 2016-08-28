---
layout: default
title: Authentication
---
## Authentication

A [service] is a `Kleisli[Task, Request, Response]`, the composable version of
`Request => Task[Response]`. http4s provides an alias called
`Service[Request, Response]`. A service with authentication also requires some
kind of `User` object which identifies which user did the request. So the
service has the signature `(User, Request) => Task[Response]`, or
`Service[(User, Request), Response]`. So we'll need a `Request => User`
function, or more likely, a `Request => Task[User]`, because the `User` will
come from a database. Which is a `Service[Request, User]`, which we can compose
with the `Service[(User, Request), Response]` to get a [service]. Or in code:

```tut:book
import scalaz._, Scalaz._, scalaz.concurrent.Task
import org.http4s._

case class User(id: Long, name: String)

def addInput[I, T](k: Service[I, T]): Service[I, (T, I)] =
  Kleisli(input => k.run(input).map(user => (user, input)))

val authUser: Service[Request, User] = Kleisli(_ => Task.delay(???))
val authedService: Service[(User, Request), Response] = Kleisli(_ => Task.delay(???))
val service: HttpService = authedService.compose(addInput(authUser))
```

## Returning an error Response

Usually, it should also be possible to send back a 403 in case there was no
valid login. The 403 response can be adjusted as needed, some applications use a
redirect to a login page, or a popup requesting login data. With the upcoming of
[SPA], the correct http status codes are relevant again.

### With Kleisli

To allow for failure, the `authUser` function has to be adjusted to a `Request
=> Task[String \/ User]`. So we'll need to handle that possibility. For advanced
error handling, we recommend an error [ADT] instead of a `String`.

```tut:book
import org.http4s.dsl._

val authUser: Service[Request, String \/ User] = Kleisli(_ => Task.delay(???))
val onFailure: Service[String, Response] = Kleisli(message => Forbidden(message))
val service: HttpService = addInput(authUser).flatMapK({
  case (maybeUser, request) =>
    maybeUser.fold(
      onFailure.run,
      authedService.local({user: User => (user, request)}).run
    )
})
```

### With minimal Kleisli Usage

```tut:book
import org.http4s.dsl._

val authUser: Request => Task[String \/ User] = {_ => Task.delay(???)}
val onFailure: String => Task[Response] = {message => Forbidden(message)}
val service: HttpService =
  Kleisli({ request =>
    authUser(request).flatMap(
      _.fold(
        onFailure,
        {user => authedService.run.apply((user, request))}
      )
    )
  })
```

## Implementing authUser

There's a few different ways to send authorization information with a HTTP
request, the two most common are cookie for regular browser usage or the
`Authorization` header for [SPA].

### Cookies

We'll use a small library for the signing/validation of the cookies, which
basically contains the specific code used by the Play framework for this
specific task.

```scala
libraryDependencies += "org.reactormonk" %% "cryptobits" % "1.0"
```

First, we'll need to set the cookie. For the crypto instance, we'll need to
provide a private key. You usually want to set a static secret so people don't
lose their session on server restarts, and a static secret also allows you to
use multiple application instances.

The message is simply the user id.

```tut:book
import org.reactormonk.{CryptoBits, PrivateKey}
import java.time._

val key = PrivateKey(scala.io.Codec.toUTF8(scala.util.Random.alphanumeric.take(20).mkString("")))
val crypto = CryptoBits(key)
val clock = Clock.systemUTC

def verifyLogin(request: Request): Task[String \/ User] = ??? // gotta figure out how to do the form
val logIn: Service[Request, Response] = Kleisli({ request =>
  verifyLogin(request: Request).flatMap(_ match {
    case -\/(error) =>
      Forbidden(error)
    case \/-(user) => {
      val message = crypto.signToken(user.id.toString, clock.millis.toString)
      Ok("Logged in!").addCookie(Cookie("authcookie", message))
    }
  })
})
```

Now that the cookie is set, we can retrieve it again in the `authUser`.

```tut:book
def retrieveUser: Service[Long, User] = Kleisli(id => Task.delay(???))
val authUser: Service[Request, String \/ User] = Kleisli({ request =>
  val message = for {
    header <- headers.Cookie.from(request.headers).toRightDisjunction("Cookie parsing error")
    cookie <- header.values.list.find(_.name == "authcookie").toRightDisjunction("Couldn't find the authcookie")
    token <- crypto.validateSignedToken(cookie.content).toRightDisjunction("Cookie invalid")
    message <- \/.fromTryCatchNonFatal(token.toLong).leftMap(_.toString)
  } yield message
  message.map(retrieveUser).sequence
})
```

### Authorization Header

There is no inherent way to set the Authorization header, send the token in any
way that your [SPA] understands. Retrieve the header value in the `authUser`
function.

```tut:book
import org.http4s.util.string._

val authUser: Service[Request, String \/ User] = Kleisli({ request =>
  val message = for {
    header <- request.headers.get("Authorization".ci).toRightDisjunction("Couldn't find an Authorization header")
    token <- crypto.validateSignedToken(header.value).toRightDisjunction("Cookie invalid")
    message <- \/.fromTryCatchNonFatal(token.toLong).leftMap(_.toString)
  } yield message
  message.map(retrieveUser).sequence
})
```

[service]: service.html
[SPA]: https://en.wikipedia.org/wiki/Single-page_application
[ADT]: http://typelevel.org/blog/2014/11/10/why_is_adt_pattern_matching_allowed.html