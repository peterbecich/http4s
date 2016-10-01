package org.http4s

import fs2._

import cats.data.Kleisli
import cats.data.Xor
import cats.data.Xor.Right
import cats.data.Xor.Left

package object server {
  /**
   * A middleware is a function of one [[Service]] to another, possibly of a
   * different [[Request]] and [[Response]] type.  http4s comes with several
   * middlewares for composing common functionality into services.
   *
   * @tparam A the request type of the original service
   * @tparam B the response type of the original service
   * @tparam C the request type of the resulting service
   * @tparam D the response type of the resulting service
   */
  type Middleware[A, B, C, D] = Service[A, B] => Service[C, D]

  object Middleware {
    def apply[A, B, C, D](f: (C, Service[A, B]) => Task[D]): Middleware[A, B, C, D] = {
      service => Service.lift {
        req => f(req, service)
      }
    }
  }

  /**
   * An HTTP middleware converts an [[HttpService]] to another.
   */
  type HttpMiddleware = Middleware[Request, Response, Request, Response]
  /**
   * An HTTP middleware that authenticates users.
   */
  type AuthMiddleware[T] = Middleware[AuthedRequest[T], Response, Request, Response]

  object AuthMiddleware {
    def apply[T](authUser: Service[Request, T]): AuthMiddleware[T] = {
      service => service.compose(AuthedRequest(authUser))
    }
    def apply[Err, T](authUser: Service[Request, Xor[Err,T]], onFailure: Kleisli[Task, AuthedRequest[Err], Response]): AuthMiddleware[T] = { service =>
      (onFailure ||| service)
        .local({authed: AuthedRequest[Xor[Err,T]] => authed.authInfo.bimap(err => AuthedRequest(err, authed.req), suc => AuthedRequest(suc, authed.req))})
        .compose(AuthedRequest(authUser))
    }
  }
}
