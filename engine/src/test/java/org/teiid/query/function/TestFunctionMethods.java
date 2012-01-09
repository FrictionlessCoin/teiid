/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.query.function;

import static org.junit.Assert.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.teiid.core.util.TimestampWithTimezone;
import org.teiid.query.unittest.TimestampUtil;

@SuppressWarnings("nls")
public class TestFunctionMethods {
	
	@BeforeClass public static void oneTimeSetup() {
		TimestampWithTimezone.ISO8601_WEEK = true;
		TimestampWithTimezone.resetCalendar(null);
	}
	
	@AfterClass public static void oneTimeTearDown() {
		TimestampWithTimezone.ISO8601_WEEK = false;
		TimestampWithTimezone.resetCalendar(null);
	}
	
	@Test public void testUnescape() {
		assertEquals("a\t\n\n%6", FunctionMethods.unescape("a\\t\\n\\012\\456"));
	}
	
	@Test public void testUnescape1() {
		assertEquals("a\u45AA'", FunctionMethods.unescape("a\\u45Aa\'"));
	}
	
	@Test public void testIso8601Week() {
		assertEquals(53, FunctionMethods.week(TimestampUtil.createDate(105, 0, 1)));
	}
	
	@Test public void testIso8601Week1() {
		assertEquals(52, FunctionMethods.week(TimestampUtil.createDate(106, 0, 1)));
	}
	
	@Test public void testIso8601Week2() {
		assertEquals(1, FunctionMethods.dayOfWeek(TimestampUtil.createDate(111, 10, 28)));
	}


}