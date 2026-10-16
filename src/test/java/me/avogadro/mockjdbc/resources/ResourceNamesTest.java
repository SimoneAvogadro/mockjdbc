package me.avogadro.mockjdbc.resources;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

public final class ResourceNamesTest {

	@Test
	public void testKeyIsLowerCaseWithQuestionMark() {
		Assert.assertEquals("users", ResourceNames.key(" Users "));
		Assert.assertEquals("users?smith,34", ResourceNames.key("users@Smith,34"));
		Assert.assertEquals("users?smith,34", ResourceNames.key("USERS?Smith,34"));
		Assert.assertEquals("users?smith#2", ResourceNames.key("users@smith#2"));
		Assert.assertEquals("##step0", ResourceNames.key("##STEP0"));
	}

	@Test
	public void testOnlyTheFirstSeparatorSeparatesTheParameters() {
		Assert.assertEquals("users?mario@example.com", ResourceNames.key("users@mario@example.com"));
		Assert.assertEquals("users?mario@example.com", ResourceNames.key("users?mario@example.com"));
		Assert.assertEquals("users?a?b", ResourceNames.key("users@a?b"));
	}

	@Test
	public void testSpellings() {
		Assert.assertEquals(Arrays.asList("users"), ResourceNames.spellings("users"));
		Assert.assertEquals(Arrays.asList("users?Smith", "users@Smith"), ResourceNames.spellings(" users?Smith "));
		Assert.assertEquals(Arrays.asList("users@Smith", "users?Smith"), ResourceNames.spellings("users@Smith"));
	}

	@Test
	public void testFileName() {
		Assert.assertEquals("Users", ResourceNames.toFileName("Users"));
		Assert.assertEquals("users#2", ResourceNames.toFileName("users#2"));
		Assert.assertEquals("users@Smith,34#2", ResourceNames.toFileName("users?Smith,34#2"));
		Assert.assertEquals("events@10%3A30", ResourceNames.toFileName("events?10:30"));
		Assert.assertEquals("a@b%3Fc%2Fd%5Ce%2Af%22g%3Ch%3Ei%7Cj%25k", ResourceNames.toFileName("a?b?c/d\\e*f\"g<h>i|j%k"));
		Assert.assertEquals("a@b%09c", ResourceNames.toFileName("a?b\tc"));
		Assert.assertEquals("a@b%2E", ResourceNames.toFileName("a?b."));
		Assert.assertEquals("a@b %2E", ResourceNames.toFileName("a?b ."));
		Assert.assertEquals("a@b", ResourceNames.toFileName(" a?b "));
		Assert.assertEquals("dbo.users", ResourceNames.toFileName("dbo.users"));
	}

	@Test
	public void testFileNameRoundTrip() {
		for (String name : new String[] { "users", "users@Smith,34#2", "events@10:30", "a@b?c/d\\e*f\"g<h>i|j%k",
				"caffè@€", "x@y.", "😀@z" }) {
			Assert.assertEquals(name, ResourceNames.fromFileName(ResourceNames.toFileName(name)));
		}
	}

	@Test
	public void testDecoding() {
		Assert.assertEquals("events@10:30", ResourceNames.fromFileName("events@10%3a30"));
		Assert.assertEquals("caffè", ResourceNames.fromFileName("caff%C3%A8"));
		// not an escape: kept as it is
		Assert.assertEquals("100%", ResourceNames.fromFileName("100%"));
		Assert.assertEquals("a%zz", ResourceNames.fromFileName("a%zz"));
		Assert.assertEquals("a%4", ResourceNames.fromFileName("a%4"));
	}

	@Test
	public void testCsvFiles() {
		Assert.assertTrue(ResourceNames.isCsvFile("users.csv"));
		Assert.assertTrue(ResourceNames.isCsvFile("Users.CSV"));
		Assert.assertFalse(ResourceNames.isCsvFile(".csv"));
		Assert.assertFalse(ResourceNames.isCsvFile("users.txt"));
		Assert.assertFalse(ResourceNames.isCsvFile("users.csv.bak"));
		Assert.assertEquals("events@10:30", ResourceNames.resourceNameOfFile("events@10%3A30.CSV"));
	}
}
