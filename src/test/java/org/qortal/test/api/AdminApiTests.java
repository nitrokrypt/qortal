package org.qortal.test.api;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.resource.AdminResource;
import org.qortal.test.common.ApiCommon;

public class AdminApiTests extends ApiCommon {

	private AdminResource adminResource;

	@Before
	public void buildResource() {
		this.adminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class);
	}

	@Test
	public void testInfo() {
		assertNotNull(this.adminResource.info());
	}

	@Test
	public void testSummary() {
		assertNotNull(this.adminResource.summary());
	}

}
