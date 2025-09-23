package com.dwolla.lambda.cloudflare.record

import cats.*
import cats.syntax.all.*

sealed trait CreateOrUpdate[+A] extends Product with Serializable {
  val value: A
  def create: Option[A] = None
  def update: Option[A] = None
}

final case class Create[A](value: A) extends CreateOrUpdate[A] {
  override def create: Option[A] = Some(value)
}

final case class Update[A](value: A) extends CreateOrUpdate[A] {
  override def update: Option[A] = Some(value)
}

object CreateOrUpdate {

  def create[A](a: A): CreateOrUpdate[A] = Create(a)
  def update[A](a: A): CreateOrUpdate[A] = Update(a)

  implicit val traverseInstance: Traverse[CreateOrUpdate] = new Traverse[CreateOrUpdate] {
    override def traverse[G[_]: Applicative, A, B](fa: CreateOrUpdate[A])
                                                  (f: A => G[B]): G[CreateOrUpdate[B]] =
      fa match {
        case Create(a) => f(a).map(Create(_))
        case Update(a) => f(a).map(Update(_))
      }

    override def foldLeft[A, B](fa: CreateOrUpdate[A], b: B)(f: (B, A) => B): B = f(b, fa.value)
    override def foldRight[A, B](fa: CreateOrUpdate[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = f(fa.value, lb)
  }

  implicit def createOrUpdateEq[A: Eq]: Eq[CreateOrUpdate[A]] =
    (x: CreateOrUpdate[A], y: CreateOrUpdate[A]) => (x, y) match {
      case (Create(a), Create(b)) => Eq[A].eqv(a, b)
      case (Update(a), Update(b)) => Eq[A].eqv(a, b)
      case _ => false
    }

}
