# Kotlin Toolchain + Leaf Enum Conversion Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable mixed Kotlin/Java compilation in the `control/` Maven module and convert two leaf enums (`ReleaseReason`, `ScrambleLevel`) to idiomatic Kotlin as proof the toolchain works.

**Architecture:** Add `kotlin-maven-plugin` with joint compilation (Kotlin compiles first, then Java compiles against Kotlin output). Reconfigure `maven-compiler-plugin` to run in `none`/custom phases. Convert two leaf enums that have no dependencies on other domain classes, preserving full Java interop for the still-Java callers (`ScramblePredictor.java`, `ScrambleForecast.java`, tests).

**Tech Stack:** Maven 3.x, Spring Boot 3.3.1 (manages Kotlin 1.9.x + jackson-module-kotlin), Kotlin 1.9.x, Java 21 (compiler target), JUnit 5

---

## File Structure

| Action | File |
|--------|------|
| Modify | `control/pom.xml` |
| Create | `control/src/main/kotlin/com/resequencetwin/control/pbs/ReleaseReason.kt` |
| Create | `control/src/main/kotlin/com/resequencetwin/control/pbs/ScrambleLevel.kt` |
| Delete | `control/src/main/java/com/resequencetwin/control/pbs/ReleaseReason.java` |
| Delete | `control/src/main/java/com/resequencetwin/control/pbs/ScrambleLevel.java` |
| Create | `control/src/main/kotlin/com/resequencetwin/control/pbs/` (directory only) |

---

## Chunk 1: pom.xml — Add Kotlin Runtime Dependencies

### Task 1: Add Kotlin + Jackson Kotlin runtime dependencies to pom.xml

**Files:**
- Modify: `control/pom.xml`

The Spring Boot 3.3.1 parent (`spring-boot-starter-parent`) manages Kotlin versions and Jackson versions. Do NOT specify `<version>` for any of these — the parent pins them.

- [ ] **Step 1: Add the four runtime dependencies inside `<dependencies>` in `control/pom.xml`**

Add the following after the existing `jackson-databind` dependency:

```xml
<!-- Kotlin runtime — versions managed by Spring Boot parent -->
<dependency>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-stdlib</artifactId>
</dependency>
<dependency>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-reflect</artifactId>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.module</groupId>
  <artifactId>jackson-module-kotlin</artifactId>
</dependency>
<dependency>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-test-junit5</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify the pom.xml is still valid XML**

Run:
```
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml validate -q
```
Expected: no errors.

---

## Chunk 2: pom.xml — Add kotlin-maven-plugin and reconfigure maven-compiler-plugin

### Task 2: Add kotlin-maven-plugin with joint compilation config

**Files:**
- Modify: `control/pom.xml` `<build><plugins>` section

The `kotlin-maven-plugin` must run Kotlin compilation BEFORE Java so Java classes can import Kotlin classes. This requires:
1. `kotlin-maven-plugin` executions `compile` and `test-compile` that include BOTH `src/main/kotlin` + `src/main/java` as source dirs.
2. `maven-compiler-plugin` executions for Java that run AFTER Kotlin — achieved by setting the default Java executions to `<phase>none</phase>` and adding new explicit executions in `compile`/`test-compile` phases.

- [ ] **Step 1: Add `kotlin-maven-plugin` to `<build><plugins>` in `control/pom.xml`**

Add this after the `spring-boot-maven-plugin` block:

```xml
<!-- Kotlin joint compilation: Kotlin compiles first, then Java compiles against Kotlin output -->
<plugin>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-maven-plugin</artifactId>
  <configuration>
    <args>
      <arg>-Xjsr305=strict</arg>
    </args>
    <compilerPlugins>
      <plugin>spring</plugin>
    </compilerPlugins>
  </configuration>
  <dependencies>
    <dependency>
      <groupId>org.jetbrains.kotlin</groupId>
      <artifactId>kotlin-maven-allopen</artifactId>
      <version>${kotlin.version}</version>
    </dependency>
  </dependencies>
  <executions>
    <execution>
      <id>compile</id>
      <goals>
        <goal>compile</goal>
      </goals>
      <configuration>
        <sourceDirs>
          <sourceDir>${project.basedir}/src/main/kotlin</sourceDir>
          <sourceDir>${project.basedir}/src/main/java</sourceDir>
        </sourceDirs>
      </configuration>
    </execution>
    <execution>
      <id>test-compile</id>
      <goals>
        <goal>test-compile</goal>
      </goals>
      <configuration>
        <sourceDirs>
          <sourceDir>${project.basedir}/src/test/kotlin</sourceDir>
          <sourceDir>${project.basedir}/src/test/java</sourceDir>
        </sourceDirs>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 2: Add reconfigured `maven-compiler-plugin` to `<build><plugins>` in `control/pom.xml`**

Add this after the kotlin-maven-plugin block:

```xml
<!-- Java compiles AFTER Kotlin: disable default executions, add explicit post-Kotlin ones -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <executions>
    <!-- Disable the default Java compile phases so Kotlin runs first -->
    <execution>
      <id>default-compile</id>
      <phase>none</phase>
    </execution>
    <execution>
      <id>default-testCompile</id>
      <phase>none</phase>
    </execution>
    <!-- Explicit Java compile executions that run after kotlin-maven-plugin -->
    <execution>
      <id>java-compile</id>
      <phase>compile</phase>
      <goals>
        <goal>compile</goal>
      </goals>
    </execution>
    <execution>
      <id>java-test-compile</id>
      <phase>test-compile</phase>
      <goals>
        <goal>testCompile</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 3: Validate pom.xml parses correctly**

Run:
```
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml validate -q
```
Expected: exits 0, no error output.

---

## Chunk 3: Create Kotlin source directories

### Task 3: Create the Kotlin source directory tree

**Files:**
- Create dirs: `control/src/main/kotlin/com/resequencetwin/control/pbs/`
- Create dirs: `control/src/test/kotlin/` (needed by the pom test-compile sourceDir config even if empty)

Maven will not fail if these dirs are empty, but they must exist so the `sourceDirs` in the pom point to real paths (some Maven versions warn/error if listed source dirs are missing).

- [ ] **Step 1: Create the Kotlin main source directories**

Create the directory structure:
- `control/src/main/kotlin/com/resequencetwin/control/pbs/`

PowerShell:
```powershell
New-Item -ItemType Directory -Force "control\src\main\kotlin\com\resequencetwin\control\pbs"
New-Item -ItemType Directory -Force "control\src\test\kotlin"
```

Or Bash:
```bash
mkdir -p control/src/main/kotlin/com/resequencetwin/control/pbs
mkdir -p control/src/test/kotlin
```

- [ ] **Step 2: Do a baseline build with no Kotlin files yet**

Run:
```
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test
```
Expected: BUILD SUCCESS, all 97 tests pass (2 skipped). If this fails, the pom changes broke something — fix the pom before continuing.

---

## Chunk 4: Convert ReleaseReason.java → ReleaseReason.kt

### Task 4: Create ReleaseReason.kt and delete the Java original

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/pbs/ReleaseReason.kt`
- Delete: `control/src/main/java/com/resequencetwin/control/pbs/ReleaseReason.java`

`ReleaseReason` is a simple enum with two constants and KDoc. No constants, no factory methods, no companion needed. Java callers use it as `ReleaseReason.OVERDUE_OVERRIDE` / `ReleaseReason.HIGHEST_SCORE` — Kotlin enum constants are accessible from Java by default.

- [ ] **Step 1: Create `ReleaseReason.kt`**

Create `control/src/main/kotlin/com/resequencetwin/control/pbs/ReleaseReason.kt` with:

```kotlin
package com.resequencetwin.control.pbs

/**
 * Why [DynamicSequencingPolicy] chose a particular body for release (rev3 R4a).
 *
 * This enum makes the policy's release decision *auditable*: every release can be
 * attributed to exactly one of the two decision branches in
 * [DynamicSequencingPolicy.selectRelease].
 */
enum class ReleaseReason {

    /**
     * The anti-starvation hard override fired: at least one lane-front body was overdue
     * (`dueDateSeq <= assemblyOut().size()`), so the overdue body with the smallest
     * `dueDateSeq` was released regardless of its multi-objective score.
     */
    OVERDUE_OVERRIDE,

    /**
     * No lane-front body was overdue, so the release was the candidate with the highest
     * multi-objective weighted score.
     */
    HIGHEST_SCORE
}
```

- [ ] **Step 2: Delete the Java original**

Delete: `control/src/main/java/com/resequencetwin/control/pbs/ReleaseReason.java`

PowerShell:
```powershell
Remove-Item "control\src\main\java\com\resequencetwin\control\pbs\ReleaseReason.java"
```

Or Bash:
```bash
rm control/src/main/java/com/resequencetwin/control/pbs/ReleaseReason.java
```

- [ ] **Step 3: Build and test — ReleaseReason round-trip**

Run:
```
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test
```
Expected: BUILD SUCCESS, all 97 tests pass (2 skipped). If compilation fails, check that `ReleaseReason.kt` has exactly the same package and enum constant names.

---

## Chunk 5: Convert ScrambleLevel.java → ScrambleLevel.kt

### Task 5: Create ScrambleLevel.kt and delete the Java original

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/pbs/ScrambleLevel.kt`
- Delete: `control/src/main/java/com/resequencetwin/control/pbs/ScrambleLevel.java`

`ScrambleLevel` has:
1. Three enum constants: `LOW`, `MEDIUM`, `HIGH`
2. Two threshold constants: `MEDIUM_THRESHOLD = 0.35`, `HIGH_THRESHOLD = 0.65`
3. A factory: `fromRiskScore(double): ScrambleLevel`

Java callers use `ScrambleLevel.MEDIUM_THRESHOLD` (field access) and `ScrambleLevel.fromRiskScore(x)` (static call). In Kotlin these must live in a `companion object` with `@JvmField` / `@JvmStatic` so Java can access them without the `Companion` qualifier.

- [ ] **Step 1: Create `ScrambleLevel.kt`**

Create `control/src/main/kotlin/com/resequencetwin/control/pbs/ScrambleLevel.kt` with:

```kotlin
package com.resequencetwin.control.pbs

/**
 * Coarse risk band for a [ScrambleForecast] (rev3 R4a).
 *
 * Bands partition the continuous `riskScore` in [0,1] into three operator-facing levels:
 * - [LOW]    — `riskScore < 0.35`
 * - [MEDIUM] — `0.35 <= riskScore < 0.65`
 * - [HIGH]   — `riskScore >= 0.65`
 *
 * The thresholds are transparent heuristic cut-points, not learned. Use
 * [fromRiskScore] to classify a score consistently.
 */
enum class ScrambleLevel {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        /** Lower bound (inclusive) of the MEDIUM band. */
        @JvmField
        val MEDIUM_THRESHOLD: Double = 0.35

        /** Lower bound (inclusive) of the HIGH band. */
        @JvmField
        val HIGH_THRESHOLD: Double = 0.65

        /**
         * Classify a risk score into its band.
         *
         * @param riskScore risk score in [0,1]
         * @return the matching level
         */
        @JvmStatic
        fun fromRiskScore(riskScore: Double): ScrambleLevel = when {
            riskScore >= HIGH_THRESHOLD   -> HIGH
            riskScore >= MEDIUM_THRESHOLD -> MEDIUM
            else                          -> LOW
        }
    }
}
```

Key interop notes:
- `@JvmField` on `MEDIUM_THRESHOLD`/`HIGH_THRESHOLD` makes them accessible as `ScrambleLevel.MEDIUM_THRESHOLD` (static field) from Java — without it Java would need `ScrambleLevel.Companion.getMEDIUM_THRESHOLD()`.
- `@JvmStatic` on `fromRiskScore` makes it callable as `ScrambleLevel.fromRiskScore(x)` from Java — without it Java would need `ScrambleLevel.Companion.fromRiskScore(x)`.

- [ ] **Step 2: Delete the Java original**

Delete: `control/src/main/java/com/resequencetwin/control/pbs/ScrambleLevel.java`

PowerShell:
```powershell
Remove-Item "control\src\main\java\com\resequencetwin\control\pbs\ScrambleLevel.java"
```

Or Bash:
```bash
rm control/src/main/java/com/resequencetwin/control/pbs/ScrambleLevel.java
```

- [ ] **Step 3: Build and test — full suite**

Run:
```
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test
```
Expected: BUILD SUCCESS, all 97 tests pass (2 skipped). If `ScrambleLevel.MEDIUM_THRESHOLD` / `fromRiskScore` can't be found by Java, check `@JvmField` / `@JvmStatic` annotations are present and the companion is inside the enum body (after the last enum constant + semicolon).

---

## Chunk 6: Commit

### Task 6: Commit on `build/scaffold`

**Files:** all modified/created/deleted files above

- [ ] **Step 1: Verify branch is `build/scaffold`**

Run:
```bash
git -C "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" branch --show-current
```
Expected: `build/scaffold`

- [ ] **Step 2: Stage all changes**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add control/pom.xml
git add control/src/main/kotlin/com/resequencetwin/control/pbs/ReleaseReason.kt
git add control/src/main/kotlin/com/resequencetwin/control/pbs/ScrambleLevel.kt
git rm control/src/main/java/com/resequencetwin/control/pbs/ReleaseReason.java
git rm control/src/main/java/com/resequencetwin/control/pbs/ScrambleLevel.java
```

- [ ] **Step 3: Commit**

```bash
git -C "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" commit -m "$(cat <<'EOF'
refactor(kotlin-1): toolchain + leaf enums

Add kotlin-maven-plugin joint compilation (Kotlin-first, then Java).
Convert ReleaseReason and ScrambleLevel to idiomatic Kotlin with full
Java interop (@JvmField/@JvmStatic companion). All 97 tests pass.
EOF
)"
```

- [ ] **Step 4: Record commit SHA**

Run:
```bash
git -C "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" log --oneline -1
```
Report the SHA in the final summary.

---

## Toolchain Gotchas for Next Conversion Batches

1. **Semicolon after last enum constant** in Kotlin enums that have a `companion object` or member declarations — required by the language (`LOW, MEDIUM, HIGH;`).
2. **`@JvmField` vs `const`**: Use `@JvmField val` for `Double` constants (cannot be `const` — only compile-time primitives/String can). Use `const val` only for `Int`/`Long`/`String`/`Boolean`/etc.
3. **`@JvmStatic` is mandatory** for any factory/utility in a companion that Java calls without the `Companion` qualifier.
4. **Source dir ordering matters**: In the `kotlin-maven-plugin` `sourceDirs`, list `kotlin` before `java` so the Kotlin compiler processes Kotlin files first.
5. **`default-compile` phase=none**: If you forget to suppress the default Java compile execution, Maven runs Java compilation twice — once before Kotlin (which fails if Java imports Kotlin), and once after. Always set `default-compile` and `default-testCompile` to `<phase>none</phase>`.
6. **Spring Boot parent manages kotlin.version**: Never hard-code a Kotlin version in `<version>` tags for `kotlin-stdlib`, `kotlin-reflect`, or `kotlin-test-junit5`. The `kotlin-maven-allopen` plugin dependency is the one exception where you must use `${kotlin.version}` (a property the parent defines).
7. **Empty `src/test/kotlin/`**: The pom references `${project.basedir}/src/test/kotlin` as a test source dir. Some Maven versions warn or fail if this path doesn't exist. Always `mkdir` it even if empty.
