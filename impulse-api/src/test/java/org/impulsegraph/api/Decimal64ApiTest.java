package org.impulsegraph.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class Decimal64ApiTest {

	@Test
	void parseAndFormatExactWithoutFloatDrift() {
		Decimal64 d1 = Decimal64.parse("123.45", 10, 2);
		assertEquals(12345L, d1.unscaled());
		assertEquals("123.45", d1.toDecimalString());
		assertEquals(new BigDecimal("123.45"), d1.toBigDecimal());

		Decimal64 d2 = Decimal64.parse("-0.05", 10, 2);
		assertEquals(-5L, d2.unscaled());
		assertEquals("-0.05", d2.toDecimalString());
		assertEquals(new BigDecimal("-0.05"), d2.toBigDecimal());

		Decimal64 d3 = Decimal64.parse("0.00", 10, 2);
		assertEquals(0L, d3.unscaled());
		assertEquals("0.00", d3.toDecimalString());

		Decimal64 d4 = Decimal64.parse("42", 10, 2);
		assertEquals(4200L, d4.unscaled());
		assertEquals("42.00", d4.toDecimalString());

		Decimal64 d5 = Decimal64.parse("999999999999999999", 18, 0);
		assertEquals(999_999_999_999_999_999L, d5.unscaled());
		assertEquals("999999999999999999", d5.toDecimalString());

		Decimal64 d6 = Decimal64.parse("-0.000000000000000001", 18, 18);
		assertEquals(-1L, d6.unscaled());
		assertEquals("-0.000000000000000001", d6.toDecimalString());
	}

	@Test
	void fromBigDecimalHalfUpAndBoundsValidation() {
		Decimal64 rounded = Decimal64.fromBigDecimal(new BigDecimal("19.995"), 10, 2);
		assertEquals(2000L, rounded.unscaled());
		assertEquals("20.00", rounded.toDecimalString());

		Decimal64 negRounded = Decimal64.fromBigDecimal(new BigDecimal("-19.995"), 10, 2);
		assertEquals(-2000L, negRounded.unscaled());
		assertEquals("-20.00", negRounded.toDecimalString());

		assertThrows(IllegalArgumentException.class, () -> Decimal64.parse("19.995", 10, 2));
		assertThrows(ArithmeticException.class, () -> Decimal64.parse("100.00", 4, 2));
		assertThrows(IllegalArgumentException.class, () -> new Decimal64(0L, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Decimal64(0L, 19, 2));
		assertThrows(IllegalArgumentException.class, () -> new Decimal64(0L, 5, 6));
	}

	@Test
	void outputColumnMetaDecimalAndNonDecimal() {
		OutputColumnMeta dec = OutputColumnMeta.decimal("total_usd", 18, 2);
		assertTrue(dec.isDecimal());
		assertEquals("total_usd", dec.name());
		assertEquals("DECIMAL(18,2)", dec.logicalType());
		assertEquals(18, dec.precision());
		assertEquals(2, dec.scale());

		OutputColumnMeta scalar = OutputColumnMeta.of("cnt", "U64");
		assertFalse(scalar.isDecimal());
		assertEquals(0, scalar.precision());
		assertEquals(0, scalar.scale());

		assertThrows(IllegalArgumentException.class, () -> OutputColumnMeta.decimal("bad", 19, 2));
		assertThrows(IllegalArgumentException.class, () -> new OutputColumnMeta("bad", "I64", 0, 2));
	}
}
