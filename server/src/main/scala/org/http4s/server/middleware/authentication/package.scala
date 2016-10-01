package org.http4s
package server
package middleware

import fs2._

import cats.data.Xor.Right
import cats.data.Xor.Left

package object authentication {
  // A function mapping (realm, username) to password, None if no password
  // exists for that (realm, username) pair.
  type AuthenticationStore = (String, String) => Task[Option[String]]

  def challenged[A](challenge: Service[Request, Challenge \/ AuthedRequest[A]])
                   (service: AuthedService[A]): HttpService =
    Service.lift { req =>
      challenge(req) flatMap {
        case Right(authedRequest) =>
          service(authedRequest)
        case Left(challenge) =>
          Task.now(Response(Status.Unauthorized).putHeaders(`WWW-Authenticate`(challenge)))
      }
    }
}
