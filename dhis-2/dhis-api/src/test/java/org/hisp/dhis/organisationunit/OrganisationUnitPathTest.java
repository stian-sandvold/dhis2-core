/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors 
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.organisationunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code path} and {@code hierarchyLevel} are derived from the ancestor chain and memoised, so this
 * pins the three behaviours the memo depends on: it is invalidated when the parent changes, it is
 * recomputable on demand, and it can never be supplied by a client.
 */
class OrganisationUnitPathTest {

  private static OrganisationUnit unit(String uid) {
    OrganisationUnit unit = new OrganisationUnit();
    unit.setUid(uid);
    unit.setName("ou-" + uid);
    unit.setShortName("ou-" + uid);
    return unit;
  }

  @Test
  @DisplayName("path and level are derived from the ancestor chain")
  void pathIsDerivedFromAncestors() {
    OrganisationUnit root = unit("rootrootroo");
    OrganisationUnit district = unit("districtdis");
    OrganisationUnit facility = unit("facilityfac");

    district.setParent(root);
    facility.setParent(district);

    assertEquals("/rootrootroo", root.getPath());
    assertEquals("/rootrootroo/districtdis", district.getPath());
    assertEquals("/rootrootroo/districtdis/facilityfac", facility.getPath());

    assertEquals(1, root.getHierarchyLevel());
    assertEquals(2, district.getHierarchyLevel());
    assertEquals(3, facility.getHierarchyLevel());
    assertEquals(3, facility.getLevel(), "getLevel() must agree with getHierarchyLevel()");
  }

  @Test
  @DisplayName("changing the parent invalidates the memoised path and level")
  void reParentingInvalidatesTheMemo() {
    OrganisationUnit rootA = unit("rootArootAr");
    OrganisationUnit rootB = unit("rootBrootBr");
    OrganisationUnit district = unit("districtdis");
    OrganisationUnit facility = unit("facilityfac");

    district.setParent(rootA);
    facility.setParent(district);

    // Read first, so the memo is populated before the move.
    assertEquals("/rootArootAr/districtdis/facilityfac", facility.getPath());
    assertEquals(3, facility.getHierarchyLevel());

    facility.setParent(rootB);

    assertEquals(
        "/rootBrootBr/facilityfac",
        facility.getPath(),
        "a populated memo must not survive a parent change");
    assertEquals(2, facility.getHierarchyLevel());
    assertEquals(2, facility.getLevel());
  }

  @Test
  @DisplayName("updatePath() recomputes a path that is present but stale")
  void updatePathForcesRecompute() {
    OrganisationUnit root = unit("rootrootroo");
    OrganisationUnit facility = unit("facilityfac");
    facility.setParent(root);
    assertEquals("/rootrootroo/facilityfac", facility.getPath());

    // Simulate the state forceUpdatePaths() repairs: the stored value is wrong, and nothing about
    // the in-memory chain says so.
    facility.setPath("/deliberately/wrong");
    assertEquals("/deliberately/wrong", facility.getPath(), "a set value is returned as-is");

    facility.updatePath();

    assertEquals("/rootrootroo/facilityfac", facility.getPath());
    assertEquals(2, facility.getHierarchyLevel());
  }

  @Test
  @DisplayName("path can never be set from a request payload")
  void pathIsNotDeserialisable() throws Exception {
    ObjectMapper mapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    OrganisationUnit deserialised =
        mapper.readValue(
            """
            {"name":"n","shortName":"s","path":"/CLIENTSUPPLI","level":42}""",
            OrganisationUnit.class);

    // getStoredPath() feeds ACL hierarchy checks and "path LIKE ?" predicates, so a client-supplied
    // value reaching it would be an access-control problem, not just a cosmetic one.
    assertNotEquals(
        "/CLIENTSUPPLI", deserialised.getStoredPath(), "a payload must not be able to set path");
    assertNotEquals("/CLIENTSUPPLI", deserialised.getPath());
    assertEquals("/" + deserialised.getUid(), deserialised.getPath(), "path stays derived");
    assertEquals(1, deserialised.getLevel(), "level stays derived");
  }

  @Test
  @DisplayName("path is still readable for serialisation, just not writable")
  void pathIsStillSerialised() {
    ObjectMapper mapper = new ObjectMapper();
    JavaType type = mapper.constructType(OrganisationUnit.class);

    // Introspected rather than round-tripped: OrganisationUnit.getChildrenThisIfEmpty() returns
    // "this" when children is empty, so a raw writeValueAsString recurses to the nesting limit.
    BeanPropertyDefinition path =
        mapper.getSerializationConfig().introspect(type).findProperties().stream()
            .filter(p -> "path".equals(p.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("path is not a Jackson property at all"));

    assertTrue(path.hasGetter(), "path must still be serialised into API responses");
    assertFalse(
        path.couldDeserialize(), "path must not be bindable from a payload (READ_ONLY access)");
  }

  @Test
  @DisplayName("a cyclic parent graph terminates")
  void cyclicGraphTerminates() {
    OrganisationUnit a = unit("aaaaaaaaaaa");
    OrganisationUnit b = unit("bbbbbbbbbbb");
    a.setParent(b);
    b.setParent(a);

    assertEquals("/aaaaaaaaaaa/bbbbbbbbbbb/aaaaaaaaaaa", a.getPath());
    assertEquals(2, a.getHierarchyLevel());
  }
}
