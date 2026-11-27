package org.impulsegraph.scala

import org.impulsegraph.api.{Decimal64, ImpulseGraphSnapshot}
import org.impulsegraph.api.statement.RowReader

given Ordering[Decimal64] with
  override def compare(x: Decimal64, y: Decimal64): Int =
    x.toBigDecimal.compareTo(y.toBigDecimal)

extension (s: String)
  def toDecimal64(precision: Int, scale: Int): Decimal64 =
    Decimal64.parse(s, precision, scale)

  def toDecimal64: Decimal64 =
    val ps = Decimal64.inferPrecisionScale(s)
    Decimal64.parse(s, ps(0), ps(1))

extension (bd: BigDecimal)
  def toDecimal64(precision: Int, scale: Int): Decimal64 =
    Decimal64.fromBigDecimal(bd.bigDecimal, precision, scale)

extension (jbd: java.math.BigDecimal)
  def toDecimal64(precision: Int, scale: Int): Decimal64 =
    Decimal64.fromBigDecimal(jbd, precision, scale)

extension (l: Long)
  def toDecimal64(precision: Int = 18, scale: Int = 0): Decimal64 =
    Decimal64.validatePrecisionScale(precision, scale)
    val scaled = Math.multiplyExact(l, Decimal64.POW10(scale))
    new Decimal64(scaled, precision, scale)

  def toDecimal64Unscaled(precision: Int, scale: Int): Decimal64 =
    new Decimal64(l, precision, scale)

extension (d: Decimal64)
  def toScalaBigDecimal: BigDecimal =
    BigDecimal(d.toBigDecimal)

  def unary_- : Decimal64 =
    new Decimal64(Math.negateExact(d.unscaled), d.precision, d.scale)

  def abs: Decimal64 =
    new Decimal64(Math.abs(d.unscaled), d.precision, d.scale)

  def rescale(newPrecision: Int, newScale: Int): Decimal64 =
    Decimal64.fromBigDecimal(d.toBigDecimal, newPrecision, newScale)

  def <(other: Decimal64): Boolean = d.toBigDecimal.compareTo(other.toBigDecimal) < 0
  def <=(other: Decimal64): Boolean = d.toBigDecimal.compareTo(other.toBigDecimal) <= 0
  def >(other: Decimal64): Boolean = d.toBigDecimal.compareTo(other.toBigDecimal) > 0
  def >=(other: Decimal64): Boolean = d.toBigDecimal.compareTo(other.toBigDecimal) >= 0

extension (row: RowReader)
  def getDecimal64(columnIndex: Int, precision: Int, scale: Int): Decimal64 =
    new Decimal64(row.getLong(columnIndex), precision, scale)

  def getDecimal64(columnName: String, precision: Int, scale: Int): Decimal64 =
    new Decimal64(row.getLong(columnName), precision, scale)

extension (snapshot: ImpulseGraphSnapshot)
  def domainDecimal64(domainName: String, attributeName: String, nodeId: Long): Option[Decimal64] =
    Option(snapshot.getDomainAttributeDecimal64(domainName, attributeName, nodeId))

  def domainDecimal64(
      domainName: String,
      attributeName: String,
      nodeId: Long,
      precision: Int,
      scale: Int
  ): Option[Decimal64] =
    Option(snapshot.getDomainAttributeDecimal64(domainName, attributeName, nodeId, precision, scale))
