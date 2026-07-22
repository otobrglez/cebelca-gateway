package biz.cebelca.gateway

import zio.*
import zio.http.*

/** A call consumes an input `I` and produces `A`. It's a profunctor:
  *   - input side is contravariant → auth, host, request-building compose via `contramap`
  *   - output side is covariant → envelope decoding, domain mapping compose via `map`
  */
final case class Call[-I, +A](run: I => IO[CebelcaError, A]):
  def map[B](f: A => B): Call[I, B]                                 = Call(run(_).map(f))
  def mapZIO[B](f: A => IO[CebelcaError, B]): Call[I, B]            = Call(run(_).flatMap(f))
  def contramap[H](f: H => I): Call[H, A]                           = Call(h => run(f(h)))
  def contramapZIO[H](f: H => IO[CebelcaError, I]): Call[H, A]      = Call(f(_).flatMap(run))
  def dimap[H, B](f: H => I)(g: A => B): Call[H, B]                 = contramap(f).map(g)
  def retry(schedule: Schedule[Any, CebelcaError, Any]): Call[I, A] = Call(run(_).retry(schedule))

/** Request decorators. A monoid under `>>>` — auth, logging, etc. stack cleanly. */
type Mw = Request => UIO[Request]

object Mw:
  val identity: Mw = ZIO.succeed(_)

  extension (self: Mw) def >>>(that: Mw): Mw = r => self(r).flatMap(that)

  def basicAuth(token: String): Mw =
    r => ZIO.succeed(r.addHeader(Header.Authorization.Basic(token, "x")))
