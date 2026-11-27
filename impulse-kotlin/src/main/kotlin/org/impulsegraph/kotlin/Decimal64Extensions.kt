package org.impulsegraph.kotlin

import org.impulsegraph.api.Decimal64
import org.impulsegraph.api.ImpulseGraphSnapshot
import org.impulsegraph.api.statement.ImpulseStatement
import org.impulsegraph.api.statement.RowReader
import java.lang.foreign.ValueLayout
import java.math.BigDecimal
import java.nio.ByteOrder

private val LE_LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

/**
 * Parses an exact base-10 decimal string into a [Decimal64] with the specified [precision] and [scale].
 */
fun String.toDecimal64(
    precision: Int,
    scale: Int,
): Decimal64 = Decimal64.parse(this, precision, scale)

/**
 * Parses an exact base-10 decimal string into a [Decimal64], inferring minimal exact `(precision, scale)`.
 */
fun String.toDecimal64(): Decimal64 {
    val ps = Decimal64.inferPrecisionScale(this)
    return Decimal64.parse(this, ps[0], ps[1])
}

/**
 * Converts a [BigDecimal] into a [Decimal64] with the specified [precision] and [scale] using half-up rounding.
 */
fun BigDecimal.toDecimal64(
    precision: Int,
    scale: Int,
): Decimal64 = Decimal64.fromBigDecimal(this, precision, scale)

/**
 * Converts a whole [Long] value into a [Decimal64] scaled by `10^scale`.
 */
fun Long.toDecimal64(
    precision: Int = 18,
    scale: Int = 0,
): Decimal64 {
    Decimal64.validatePrecisionScale(precision, scale)
    val scaled = Math.multiplyExact(this, Decimal64.POW10[scale])
    return Decimal64(scaled, precision, scale)
}

/**
 * Wraps an already-unscaled 64-bit integer as a [Decimal64] with the given [precision] and [scale].
 */
fun Long.toDecimal64Unscaled(
    precision: Int,
    scale: Int,
): Decimal64 = Decimal64(this, precision, scale)

/**
 * Exact numeric comparison between two [Decimal64] values across arbitrary scales.
 */
operator fun Decimal64.compareTo(other: Decimal64): Int = this.toBigDecimal().compareTo(other.toBigDecimal())

/**
 * Exact negation of a [Decimal64] value.
 */
operator fun Decimal64.unaryMinus(): Decimal64 = Decimal64(Math.negateExact(unscaled()), precision(), scale())

/**
 * Exact absolute value of a [Decimal64] value.
 */
fun Decimal64.abs(): Decimal64 = Decimal64(Math.abs(unscaled()), precision(), scale())

/**
 * Rescales this [Decimal64] to `(newPrecision, newScale)` using half-up rounding.
 */
fun Decimal64.rescale(
    newPrecision: Int,
    newScale: Int,
): Decimal64 = Decimal64.fromBigDecimal(this.toBigDecimal(), newPrecision, newScale)

/**
 * Binds a [Decimal64] parameter to an [ImpulseStatement] by its exact unscaled `i64` representation.
 */
fun ImpulseStatement.bindDecimal64(
    param: String,
    value: Decimal64,
): ImpulseStatement = bindLong(param, value.unscaled())

/**
 * Reads a [Decimal64] column at [columnIndex] from a [RowReader].
 */
fun RowReader.getDecimal64(
    columnIndex: Int,
    precision: Int,
    scale: Int,
): Decimal64 = Decimal64(getLong(columnIndex), precision, scale)

/**
 * Reads a named [Decimal64] column from a [RowReader].
 */
fun RowReader.getDecimal64(
    columnName: String,
    precision: Int,
    scale: Int,
): Decimal64 = Decimal64(getLong(columnName), precision, scale)

/**
 * Reads a non-nullable [Decimal64] domain attribute value directly from the snapshot's off-heap attribute segment.
 */
fun ImpulseGraphSnapshot.getDomainDecimal64(
    domainName: String,
    attributeName: String,
    nodeId: Long,
    precision: Int,
    scale: Int,
): Decimal64 {
    val seg = getDomainAttributeSegment(domainName, attributeName)
    require(seg.address() != 0L && seg.byteSize() >= (nodeId + 1L) * 8L) {
        "Domain '$domainName' attribute '$attributeName' segment unavailable for node $nodeId"
    }
    return Decimal64(seg.get(LE_LONG, nodeId * 8L), precision, scale)
}
