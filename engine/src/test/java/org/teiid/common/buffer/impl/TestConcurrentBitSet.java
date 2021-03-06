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
 
 package org.teiid.common.buffer.impl;

import static org.junit.Assert.*;

import org.junit.Test;

public class TestConcurrentBitSet {
	
	@Test public void testBitsSet() {
		ConcurrentBitSet bst = new ConcurrentBitSet(50001, 4);
		assertEquals(0, bst.getAndSetNextClearBit());
		assertEquals(12501, bst.getAndSetNextClearBit());
		assertEquals(25002, bst.getAndSetNextClearBit());
		assertEquals(37503, bst.getAndSetNextClearBit());
		assertEquals(1, bst.getAndSetNextClearBit());
		assertEquals(5, bst.getBitsSet());
		bst.clear(1);
		assertEquals(4, bst.getBitsSet());
		bst.clear(12501);
		try {
			bst.clear(30000);
			fail();
		} catch (AssertionError e) {
			
		}
		assertEquals(3, bst.getBitsSet());
		
		for (int i = 0; i < bst.getTotalBits()-3;i++) {
			assertTrue(bst.getAndSetNextClearBit() != -1);
		}
		
		bst.clear(5);
		bst.clear(12505);
		bst.clear(25505);
		bst.clear(37505);
		
		for (int i = 0; i < 4; i++) {
			int bit = bst.getAndSetNextClearBit();
			assertTrue(bit < bst.getTotalBits() && bit > 0);
		}
	}
	
	@Test public void testSegmentUse() {
		ConcurrentBitSet bst = new ConcurrentBitSet(50001, 4);
		assertEquals(0, bst.getAndSetNextClearBit(0));
		assertEquals(1, bst.getAndSetNextClearBit(0));
		assertEquals(2, bst.getAndSetNextClearBit(4));
	}
	
	@Test public void testCompactBitSet() {
		ConcurrentBitSet bst = new ConcurrentBitSet(100000, 1);
		bst.setCompact(true);
		for (int i = 0; i < 100000; i++) {
			assertEquals(i, bst.getAndSetNextClearBit());
		}
		bst.clear(50);
		bst.clear(500);
		bst.clear(5000);
		assertEquals(50, bst.getAndSetNextClearBit());
	}
	
	@Test public void testCompactHighest() {
		ConcurrentBitSet bst = new ConcurrentBitSet(1 << 19, 1);
		bst.setCompact(true);
		for (int i = 0; i < bst.getTotalBits(); i++) {
			bst.getAndSetNextClearBit();
		}
		assertEquals(bst.getTotalBits()-1, bst.getHighestBitSet(0));
		assertEquals(bst.getTotalBits()-1, bst.getHighestBitSet(1));
		
		for (int i = bst.getTotalBits()-20; i < bst.getTotalBits(); i++) {
			bst.clear(i);
		}

		assertEquals(bst.getTotalBits()-21, bst.compactHighestBitSet(0));
		
		for (int i = bst.getTotalBits()-20; i < bst.getTotalBits(); i++) {
			bst.getAndSetNextClearBit();
		}
		
		assertEquals(-1, bst.getAndSetNextClearBit());
		
		for (int i = 20; i < bst.getTotalBits(); i++) {
			bst.clear(i);
		}
		
		assertEquals(bst.getTotalBits()-1, bst.getHighestBitSet(0));
		assertEquals(19, bst.compactHighestBitSet(0));
		
	}
	
	@Test public void testCompactHighestEmpty() {
		ConcurrentBitSet bst = new ConcurrentBitSet(1 << 19, 1);
		bst.setCompact(true);
		bst.getAndSetNextClearBit();
		bst.clear(0);
		assertEquals(-1, bst.compactHighestBitSet(0));
	}
	
}
