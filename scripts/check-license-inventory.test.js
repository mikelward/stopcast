// The suite's own failure mode is a false pass — a comparison that finds
// nothing looks exactly like one that vouched for everything — so each case
// asserts the concrete lines the report must carry, and the guard cases
// assert the refusal itself.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  FINE_LICENSES,
  auditIsClean,
  auditReport,
  classifyFlagged,
  classifyInventory,
  classifyLicense,
  compareInventories,
  report,
} from "./check-license-inventory.mjs";

const inventory = (...libs) => ({
  libraries: libs.map(([uniqueId, licenses, artifactVersion = "1.0.0"]) => ({
    uniqueId,
    licenses,
    artifactVersion,
  })),
});

describe("compareInventories", () => {
  it("does not flag a version-only change", () => {
    const result = compareInventories(
      inventory(["a:b", ["Apache-2.0"], "1.0.0"]),
      inventory(["a:b", ["Apache-2.0"], "2.0.0"]),
    );
    assert.equal(result.flagged, false);
    assert.deepEqual(result.changed, []);
    assert.deepEqual(result.added, []);
    assert.deepEqual(result.removed, []);
  });

  it("flags a changed license and names both sides", () => {
    const result = compareInventories(
      inventory(["a:b", ["Apache-2.0"]]),
      inventory(["a:b", ["GPL-3.0-only"]]),
    );
    assert.equal(result.flagged, true);
    assert.deepEqual(result.changed, ["a:b: Apache-2.0 → GPL-3.0-only"]);
  });

  it("flags a new library and attributes its license", () => {
    const result = compareInventories(
      inventory(["a:b", ["Apache-2.0"]]),
      inventory(["a:b", ["Apache-2.0"]], ["c:d", ["MIT"]]),
    );
    assert.equal(result.flagged, true);
    assert.deepEqual(result.added, ["c:d: MIT"]);
  });

  it("reports a removal without flagging", () => {
    const result = compareInventories(
      inventory(["a:b", ["Apache-2.0"]], ["c:d", ["MIT"]]),
      inventory(["a:b", ["Apache-2.0"]]),
    );
    assert.equal(result.flagged, false);
    assert.deepEqual(result.removed, ["c:d: MIT"]);
  });

  it("treats license-set ORDER as irrelevant but membership as a change", () => {
    const same = compareInventories(
      inventory(["a:b", ["MIT", "Apache-2.0"]]),
      inventory(["a:b", ["Apache-2.0", "MIT"]]),
    );
    assert.equal(same.flagged, false);
    const grew = compareInventories(
      inventory(["a:b", ["Apache-2.0"]]),
      inventory(["a:b", ["Apache-2.0", "MIT"]]),
    );
    assert.equal(grew.flagged, true);
    assert.deepEqual(grew.changed, ["a:b: Apache-2.0 → Apache-2.0, MIT"]);
  });

  it("flags an entry that loses its license declaration", () => {
    const result = compareInventories(
      inventory(["a:b", ["Apache-2.0"]]),
      inventory(["a:b", []]),
    );
    assert.equal(result.flagged, true);
    assert.deepEqual(result.changed, ["a:b: Apache-2.0 → (no license declared)"]);
  });

  it("refuses an empty inventory rather than vouching for it", () => {
    assert.throws(
      () => compareInventories({ libraries: [] }, inventory(["a:b", ["MIT"]])),
      /no libraries found/,
    );
    assert.throws(
      () => compareInventories(inventory(["a:b", ["MIT"]]), {}),
      /no libraries found/,
    );
  });

  it("refuses an entry with no uniqueId rather than guessing", () => {
    assert.throws(
      () => compareInventories(inventory(["a:b", ["MIT"]]), { libraries: [{ licenses: ["MIT"] }] }),
      /no uniqueId/,
    );
  });
});

describe("report", () => {
  it("says unchanged when nothing moved", () => {
    const text = report({ changed: [], added: [], removed: [], flagged: false }, 135);
    assert.match(text, /unchanged across 135 bundled libraries/);
  });

  it("leads with license changes and keeps removals clearly informational", () => {
    const text = report(
      { changed: ["a:b: MIT → GPL-3.0-only"], added: [], removed: ["c:d: MIT"], flagged: true },
      2,
    );
    assert.match(text, /License CHANGED/);
    assert.match(text, /a:b: MIT → GPL-3.0-only/);
    assert.match(text, /No longer bundled/);
    assert.doesNotMatch(text, /not flagging/);
  });

  it("says so explicitly when only removals happened", () => {
    const text = report({ changed: [], added: [], removed: ["c:d: MIT"], flagged: false }, 1);
    assert.match(text, /not flagging/);
  });
});

// The policy tiers. Both directions are asserted throughout: that a
// forbidden license is caught AND that the ids shipping today are not —
// a deny-list that widened to catch everything would pass a one-sided
// suite while turning every weekly batch red.
const inventoryWithNames = (licenses, ...libs) => ({ ...inventory(...libs), licenses });

describe("classifyLicense", () => {
  it("passes every id shipping today as fine", () => {
    for (const id of FINE_LICENSES) {
      assert.deepEqual(classifyLicense(id), { tier: "fine", reason: null }, id);
    }
    assert.equal(classifyLicense("Apache-2.0", "Apache License 2.0").tier, "fine");
    assert.equal(classifyLicense("BSD-3-Clause", 'BSD 3-Clause "New" or "Revised" License').tier, "fine");
    assert.equal(classifyLicense("PCSDKToS", "Play Core Software Development Kit Terms of Service").tier, "fine");
  });

  it("forbids every GPL-family variant by id", () => {
    for (const id of [
      "GPL-2.0", "GPL-2.0-only", "GPL-3.0-or-later", "GPL-2.0 WITH Classpath-exception-2.0",
      "LGPL-2.1", "LGPL-3.0-only", "AGPL-3.0", "AGPL-3.0-or-later",
    ]) {
      const verdict = classifyLicense(id);
      assert.equal(verdict.tier, "forbidden", id);
      assert.match(verdict.reason, /copyleft/);
    }
  });

  it("forbids a copyleft license by NAME when the id is an opaque hash", () => {
    assert.equal(classifyLicense("a1b2c3", "GNU General Public License v3.0").tier, "forbidden");
    assert.equal(classifyLicense("a1b2c3", "GNU Lesser General Public License v2.1").tier, "forbidden");
    assert.equal(classifyLicense("a1b2c3", "GNU Affero General Public License v3.0").tier, "forbidden");
  });

  it("forbids the version-suffixed abbreviations a POM writes by hand", () => {
    // `\b` sees `GPLv3` as one word, so a plain word-boundary match let
    // these through as `review` (Codex on PR #304). By name under an opaque
    // id, and by id, both directions.
    for (const name of ["GNU GPLv3", "GNU GPL v3", "LGPLv2.1", "LGPL v2.1", "AGPLv3", "GPL3", "GNU GPLv2 with the Classpath exception"]) {
      assert.equal(classifyLicense("a1b2c3", name).tier, "forbidden", name);
    }
    for (const id of ["GPLv3", "LGPLv2.1", "AGPLv3", "GPL3"]) {
      assert.equal(classifyLicense(id).tier, "forbidden", id);
    }
  });

  it("forbids the source-available and non-commercial terms", () => {
    for (const [id, name] of [
      ["SSPL-1.0", ""], ["x", "Server Side Public License"],
      ["BUSL-1.1", ""], ["x", "Business Source License 1.1"],
      ["CC-BY-NC-4.0", ""], ["CC-BY-NC-SA-4.0", ""], ["CC-BY-NC-ND-3.0", ""],
      ["x", "Creative Commons Attribution Non-Commercial 4.0"],
      ["x", "Apache 2.0 with Commons Clause"],
    ]) {
      assert.equal(classifyLicense(id, name).tier, "forbidden", `${id} ${name}`);
    }
  });

  it("sends weak copyleft and unrecognized ids to review, not forbidden", () => {
    for (const id of ["MPL-2.0", "EPL-2.0", "CDDL-1.1", "ISC", "BSD-2-Clause", "Unlicense", "something-new"]) {
      assert.deepEqual(classifyLicense(id), { tier: "review", reason: null }, id);
    }
  });

  it("does not mistake a permissive license for GPL on a substring", () => {
    // CC-BY (no NC) is review, not forbidden; "GPL" must be a whole word.
    assert.equal(classifyLicense("CC-BY-4.0", "Creative Commons Attribution 4.0").tier, "review");
    assert.equal(classifyLicense("OpenGPLx").tier, "review");
  });

  it("fails closed on a name that merely MENTIONS a forbidden family", () => {
    // Deliberate (Codex on PR #304 asked for the opposite): the cost of this
    // false positive is a red CI naming the library and the exact name; the
    // cost of the false negative it prevents is silent copyleft. The escape
    // hatch is the id, below — not a looser pattern.
    assert.equal(classifyLicense("a1b2c3", "MIT License (GPL compatible)").tier, "forbidden");
    assert.equal(classifyLicense("a1b2c3", "Permissive license; not GPL").tier, "forbidden");
  });

  it("lets a FINE_LICENSES id win over anything its display name says", () => {
    // The escape hatch for the case above: a human who has read the license
    // lists its id, and the name no longer matters.
    assert.equal(classifyLicense("MIT", "MIT License (GPL compatible)").tier, "fine");
    assert.equal(classifyLicense("Apache-2.0", "Apache License 2.0, not the GNU General Public License").tier, "fine");
    // ...and the same id with no listing is still caught, so the override is
    // the listing, not the id's spelling.
    assert.equal(classifyLicense("MIT-0", "MIT No Attribution (GPL compatible)").tier, "forbidden");
  });
});

describe("classifyInventory", () => {
  const names = {
    "Apache-2.0": { name: "Apache License 2.0" },
    "GPL-3.0-only": { name: "GNU General Public License v3.0 only" },
    "MPL-2.0": { name: "Mozilla Public License 2.0" },
    hash1: { name: "GNU Lesser General Public License v2.1" },
  };

  it("reports nothing for an inventory made of today's ids", () => {
    const result = classifyInventory(inventoryWithNames(names,
      ["a:b", ["Apache-2.0"]], ["c:d", ["MIT"]], ["e:f", ["BSD-3-Clause"]], ["g:h", ["ASDKL"]], ["i:j", ["PCSDKToS"]],
    ));
    assert.deepEqual(result, { forbidden: [], review: [] });
  });

  it("names the forbidden library, its license and the family, with the display name", () => {
    const result = classifyInventory(inventoryWithNames(names, ["a:b", ["Apache-2.0"]], ["c:d", ["GPL-3.0-only"]]));
    assert.deepEqual(result.forbidden, ["c:d: GPL-3.0-only (GNU General Public License v3.0 only) — GPL-family copyleft"]);
    assert.deepEqual(result.review, []);
  });

  it("catches a copyleft license declared under an opaque id, via the names map", () => {
    const result = classifyInventory(inventoryWithNames(names, ["c:d", ["hash1"]]));
    assert.equal(result.forbidden.length, 1);
    assert.match(result.forbidden[0], /c:d: hash1 \(GNU Lesser General Public License v2.1\)/);
  });

  it("takes the WORST tier for a multi-licensed library", () => {
    const result = classifyInventory(inventoryWithNames(names, ["c:d", ["Apache-2.0", "GPL-3.0-only"]]));
    assert.equal(result.forbidden.length, 1);
    assert.deepEqual(result.review, []);
    const dual = classifyInventory(inventoryWithNames(names, ["c:d", ["MIT", "MPL-2.0"]]));
    assert.deepEqual(dual.forbidden, []);
    assert.deepEqual(dual.review, ["c:d: MPL-2.0 (Mozilla Public License 2.0)"]);
  });

  it("sends a library with no license declared to review", () => {
    const result = classifyInventory(inventoryWithNames(names, ["c:d", []]));
    assert.deepEqual(result, { forbidden: [], review: ["c:d: (no license declared)"] });
  });

  it("copes with an inventory that has no licenses map at all", () => {
    const result = classifyInventory(inventory(["c:d", ["LGPL-2.1"]], ["e:f", ["ISC"]]));
    assert.deepEqual(result.forbidden, ["c:d: LGPL-2.1 — GPL-family copyleft"]);
    assert.deepEqual(result.review, ["e:f: ISC"]);
  });

  it("refuses an empty inventory rather than vouching for it", () => {
    assert.throws(() => classifyInventory({ libraries: [] }), /no libraries found/);
    assert.throws(() => classifyInventory({}), /no libraries found/);
  });
});

describe("classifyFlagged", () => {
  const names = { "GPL-3.0-only": { name: "GNU General Public License v3.0 only" } };

  it("classifies only the libraries the diff flagged", () => {
    const before = inventoryWithNames(names, ["a:b", ["Apache-2.0"]], ["x:y", ["MPL-2.0"]]);
    const after = inventoryWithNames(names, ["a:b", ["GPL-3.0-only"]], ["x:y", ["MPL-2.0"]], ["c:d", ["ISC"]]);
    const diff = compareInventories(before, after);
    assert.equal(diff.flagged, true);
    const verdicts = classifyFlagged(diff, after);
    assert.deepEqual(verdicts.forbidden, ["a:b: GPL-3.0-only (GNU General Public License v3.0 only) — GPL-family copyleft"]);
    // c:d is new and unclassified; x:y is review-tier but unchanged, so it is
    // the diff's business last week, not this one's.
    assert.deepEqual(verdicts.review, ["c:d: ISC"]);
  });

  it("returns nothing when the flagged libraries are all fine", () => {
    const before = inventory(["a:b", ["Apache-2.0"]]);
    const after = inventory(["a:b", ["Apache-2.0"]], ["c:d", ["MIT"]]);
    assert.deepEqual(classifyFlagged(compareInventories(before, after), after), { forbidden: [], review: [] });
  });

  it("returns nothing for an unflagged diff without touching the inventory", () => {
    const same = inventory(["a:b", ["Apache-2.0"]]);
    assert.deepEqual(classifyFlagged(compareInventories(same, same), same), { forbidden: [], review: [] });
  });
});

describe("auditReport", () => {
  it("says all clear with the count when nothing is flagged", () => {
    assert.match(auditReport({ forbidden: [], review: [] }, 135), /All 135 bundled libraries/);
  });

  it("leads with FORBIDDEN and names the policy file for review entries", () => {
    const text = auditReport({ forbidden: ["c:d: GPL-3.0-only — GPL-family copyleft"], review: ["e:f: ISC"] }, 2);
    assert.match(text, /^FORBIDDEN/);
    assert.match(text, /c:d: GPL-3.0-only/);
    assert.match(text, /REVIEW/);
    assert.match(text, /FINE_LICENSES/);
    assert.doesNotMatch(text, /All 2 bundled/);
  });
});

describe("auditIsClean", () => {
  it("is clean only when nothing is forbidden and nothing needs review", () => {
    assert.equal(auditIsClean({ forbidden: [], review: [] }), true);
  });

  it("is not clean when a forbidden license is bundled", () => {
    assert.equal(auditIsClean({ forbidden: ["c:d: GPL-3.0-only — GPL-family copyleft"], review: [] }), false);
  });

  it("is not clean when a review-tier license is bundled — the CI-audit gate a manual PR can't slip past", () => {
    // The regression this whole change closes: a review-tier entry (an
    // unclassified or weak-copyleft id) must fail the audit, not pass green.
    assert.equal(auditIsClean({ forbidden: [], review: ["e:f: ISC"] }), false);
  });
});
