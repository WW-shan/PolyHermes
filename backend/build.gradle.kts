import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.2.0"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.spring") version "1.9.20"
    kotlin("plugin.jpa") version "1.9.20"
}

group = "com.wrbug"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    
    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // WebSocket Client
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    
    // Database
    implementation("com.mysql:mysql-connector-j:8.2.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    
    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    
    // Keccak-256 for Ethereum function selector
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    
    // Web3j for Ethereum wallet and EIP-712 signing
    implementation("org.web3j:core:5.0.0")
    
    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    implementation("io.jsonwebtoken:jjwt-impl:0.12.3")
    implementation("io.jsonwebtoken:jjwt-jackson:0.12.3")
    
    // BCrypt for password encryption
    implementation("org.springframework.security:spring-security-crypto:6.2.2")
    
    // Logging
    implementation("org.slf4j:slf4j-api")
    
    // Caffeine Cache (LRU)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    
    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

/**
 * Research-only maker backtest. It does not start Spring, touch the database, or place orders.
 *
 * Example:
 *   ./gradlew eventMakerBacktest -PresearchProxy=http://127.0.0.1:10808 \
 *       -PresearchMaxMarkets=300 -PresearchMinVolume=250000
 */
tasks.register<JavaExec>("eventMakerBacktest") {
    group = "research"
    description = "Replay public Polymarket trades through the event-maker research engine"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wrbug.polymarketbot.research.eventmaker.EventMakerBacktestCliKt")

    val argsList = mutableListOf<String>()
    providers.gradleProperty("researchProxy").orNull?.let {
        argsList += listOf("--proxy", it)
    }
    argsList += listOf("--max-markets", providers.gradleProperty("researchMaxMarkets").orNull ?: "300")
    argsList += listOf("--min-volume", providers.gradleProperty("researchMinVolume").orNull ?: "250000")
    argsList += listOf("--from", providers.gradleProperty("researchFrom").orNull ?: "2024-01-01")
    argsList += listOf("--to", providers.gradleProperty("researchTo").orNull ?: "2026-10-01")
    argsList += listOf("--concurrency", providers.gradleProperty("researchConcurrency").orNull ?: "6")
    argsList += listOf(
        "--entry-horizon-seconds",
        providers.gradleProperty("researchEntryHorizonSeconds").orNull ?: "86400"
    )
    // Optional activity gate mirroring the live discovery filter.
    providers.gradleProperty("researchMinRecentTrades").orNull?.let {
        argsList += listOf("--min-recent-trades", it)
    }
    providers.gradleProperty("researchMinRecentNotional").orNull?.let {
        argsList += listOf("--min-recent-notional", it)
    }
    providers.gradleProperty("researchMinRecentTradesPerSide").orNull?.let {
        argsList += listOf("--min-recent-trades-per-side", it)
    }
    providers.gradleProperty("researchMinRecentTrades2h").orNull?.let {
        argsList += listOf("--min-recent-trades-2h", it)
    }
    providers.gradleProperty("researchMinRecentNotional2h").orNull?.let {
        argsList += listOf("--min-recent-notional-2h", it)
    }
    providers.gradleProperty("researchMinRecentTradesPerSide2h").orNull?.let {
        argsList += listOf("--min-recent-trades-per-side-2h", it)
    }
    providers.gradleProperty("researchFlowGuardWindowSeconds").orNull?.let {
        argsList += listOf("--flow-guard-window-seconds", it)
    }
    providers.gradleProperty("researchFlowGuardMaxSignedFlow").orNull?.let {
        argsList += listOf("--flow-guard-max-signed-flow", it)
    }
    providers.gradleProperty("researchFlowGuardMinPriceDropCents").orNull?.let {
        argsList += listOf("--flow-guard-min-price-drop-cents", it)
    }
    providers.gradleProperty("researchMaxOrderAgeSeconds").orNull?.let {
        argsList += listOf("--max-order-age-seconds", it)
    }
    providers.gradleProperty("researchMinRemainingSeconds").orNull?.let {
        argsList += listOf("--min-remaining-seconds", it)
    }
    providers.gradleProperty("researchGridProfile").orNull?.let {
        argsList += listOf("--grid-profile", it)
    }
    providers.gradleProperty("researchDataset").orNull?.let {
        argsList += listOf("--dataset", it)
    }
    providers.gradleProperty("researchFocusConfig").orNull?.let {
        argsList += listOf("--focus-config", it)
    }
    if (providers.gradleProperty("researchFocusOnly").orNull == "true") {
        argsList += "--focus-only"
    }
    if (providers.gradleProperty("researchRefreshDataset").orNull == "true") {
        argsList += "--refresh-dataset"
    }
    argsList += listOf("--output", providers.gradleProperty("researchOutput").orNull ?: "build/research/event-maker-backtest")
    args(argsList)
}
/**
 * Real-time paper market-making test using live Polymarket books and public trades.
 * It never creates or signs an order.
 */
tasks.register<JavaExec>("livePaperMaker") {
    group = "research"
    description = "Run a real-time maker-only paper test against live Polymarket data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wrbug.polymarketbot.research.livepaper.LivePaperCliKt")

    val argsList = mutableListOf<String>()
    providers.gradleProperty("liveProxy").orNull?.let { argsList += listOf("--proxy", it) }
    argsList += listOf("--max-markets", providers.gradleProperty("liveMaxMarkets").orNull ?: "8")
    argsList += listOf("--min-volume", providers.gradleProperty("liveMinVolume").orNull ?: "25000")
    argsList += listOf("--min-minutes", providers.gradleProperty("liveMinMinutes").orNull ?: "120")
    argsList += listOf("--min-remaining-minutes", providers.gradleProperty("liveMinRemainingMinutes").orNull ?: "120")
    argsList += listOf("--minutes", providers.gradleProperty("liveMinutes").orNull ?: "720")
    argsList += listOf("--target-cycles", providers.gradleProperty("liveTargetCycles").orNull ?: "12")
    if (providers.gradleProperty("liveAllowNegRisk").orNull == "true") argsList += "--allow-neg-risk"
    argsList += listOf("--poll-seconds", providers.gradleProperty("livePollSeconds").orNull ?: "3")
    argsList += listOf("--snapshot-seconds", providers.gradleProperty("liveSnapshotSeconds").orNull ?: "10")
    argsList += listOf("--output", providers.gradleProperty("liveOutput").orNull ?: "build/research/live-paper")
    args(argsList)
}

/**
 * Unified Shadow runner using the production planner/risk/engine path.
 * It never signs or places an order and does not require MySQL.
 */
tasks.register<JavaExec>("eventMakerShadow") {
    group = "research"
    description = "Run the unified event-maker Shadow engine against live public Polymarket data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wrbug.polymarketbot.research.eventmaker.EventMakerShadowCliKt")

    val argsList = mutableListOf<String>()
    providers.gradleProperty("shadowProxy").orNull?.let { argsList += listOf("--proxy", it) }
    argsList += listOf("--max-markets", providers.gradleProperty("shadowMaxMarkets").orNull ?: "6")
    argsList += listOf("--discovery-pages", providers.gradleProperty("shadowDiscoveryPages").orNull ?: "50")
    argsList += listOf("--wait-for-market-minutes", providers.gradleProperty("shadowWaitForMarketMinutes").orNull ?: "0")
    argsList += listOf("--discovery-retry-seconds", providers.gradleProperty("shadowDiscoveryRetrySeconds").orNull ?: "60")
    argsList += listOf("--min-volume", providers.gradleProperty("shadowMinVolume").orNull ?: "50000")
    argsList += listOf("--min-recent-trades", providers.gradleProperty("shadowMinRecentTrades").orNull ?: "0")
    argsList += listOf("--min-recent-notional", providers.gradleProperty("shadowMinRecentNotional").orNull ?: "0")
    argsList += listOf("--min-recent-trades-per-side", providers.gradleProperty("shadowMinRecentTradesPerSide").orNull ?: "0")
    argsList += listOf("--min-recent-trades-2h", providers.gradleProperty("shadowMinRecentTrades2h").orNull ?: "0")
    argsList += listOf("--min-recent-notional-2h", providers.gradleProperty("shadowMinRecentNotional2h").orNull ?: "0")
    argsList += listOf("--min-recent-trades-per-side-2h", providers.gradleProperty("shadowMinRecentTradesPerSide2h").orNull ?: "0")
    argsList += listOf("--min-price", providers.gradleProperty("shadowMinPrice").orNull ?: "0.90")
    argsList += listOf("--max-price", providers.gradleProperty("shadowMaxPrice").orNull ?: "0.99")
    argsList += listOf("--tags", providers.gradleProperty("shadowTags").orNull ?: "Politics,Geopolitics")
    argsList += listOf("--min-remaining-minutes", providers.gradleProperty("shadowMinRemainingMinutes").orNull ?: "120")
    providers.gradleProperty("shadowMaxRemainingMinutes").orNull?.let {
        argsList += listOf("--max-remaining-minutes", it)
    }
    argsList += listOf("--minutes", providers.gradleProperty("shadowMinutes").orNull ?: "120")
    argsList += listOf("--poll-seconds", providers.gradleProperty("shadowPollSeconds").orNull ?: "3")
    argsList += listOf("--snapshot-seconds", providers.gradleProperty("shadowSnapshotSeconds").orNull ?: "10")
    argsList += listOf("--max-queue", providers.gradleProperty("shadowMaxQueue").orNull ?: "500")
    argsList += listOf("--min-spread-ticks", providers.gradleProperty("shadowMinSpreadTicks").orNull ?: "2")
    argsList += listOf("--improve-ticks", providers.gradleProperty("shadowImproveTicks").orNull ?: "1")
    argsList += listOf("--entry-offset-cents", providers.gradleProperty("shadowEntryOffsetCents").orNull ?: "0")
    argsList += listOf("--exit-improve-ticks", providers.gradleProperty("shadowExitImproveTicks").orNull ?: "1")
    argsList += listOf("--exit-spread-cents", providers.gradleProperty("shadowExitSpreadCents").orNull ?: "1")
    argsList += listOf("--requote-seconds", providers.gradleProperty("shadowRequoteSeconds").orNull ?: "120")
    argsList += listOf("--max-order-age-seconds", providers.gradleProperty("shadowMaxOrderAgeSeconds").orNull ?: "3600")
    argsList += listOf("--tail-window-minutes", providers.gradleProperty("shadowTailWindowMinutes").orNull ?: "0")
    argsList += listOf("--flow-guard-window-seconds", providers.gradleProperty("shadowFlowGuardWindowSeconds").orNull ?: "3600")
    argsList += listOf("--flow-guard-max-signed-flow", providers.gradleProperty("shadowFlowGuardMaxSignedFlow").orNull ?: "-0.4")
    argsList += listOf("--flow-guard-min-price-drop-cents", providers.gradleProperty("shadowFlowGuardMinPriceDropCents").orNull ?: "0.5")
    argsList += listOf("--stale-book-seconds", providers.gradleProperty("shadowStaleBookSeconds").orNull ?: "30")
    argsList += listOf("--active-order-stale-seconds", providers.gradleProperty("shadowActiveOrderStaleSeconds").orNull ?: "600")
    argsList += listOf("--order-notional", providers.gradleProperty("shadowOrderNotional").orNull ?: "25")
    argsList += listOf("--fill-volume-factor", providers.gradleProperty("shadowFillVolumeFactor").orNull ?: "1")
    argsList += listOf("--max-cycles-per-market", providers.gradleProperty("shadowMaxCyclesPerMarket").orNull ?: "3")
    argsList += listOf("--max-total-inventory-notional", providers.gradleProperty("shadowMaxTotalInventoryNotional").orNull ?: "100")
    argsList += listOf("--max-daily-loss", providers.gradleProperty("shadowMaxDailyLoss").orNull ?: "5")
    if (providers.gradleProperty("shadowAllowNegRisk").orNull == "true") argsList += "--allow-neg-risk"
    argsList += listOf("--output", providers.gradleProperty("shadowOutput").orNull ?: "build/research/event-maker-shadow")
    args(argsList)
}

/**
 * Research-only paired liquidity-reward shadow. It subscribes to both outcome
 * tokens, simulates two post-only bids, and hedges a one-sided fill after a
 * grace period. It never signs or submits an order.
 */
tasks.register<JavaExec>("pairedRewardShadow") {
    group = "research"
    description = "Run the paired liquidity-reward shadow against live public Polymarket data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wrbug.polymarketbot.research.eventmaker.PairedRewardShadowCliKt")

    val argsList = mutableListOf<String>()
    providers.gradleProperty("pairedRewardProxy").orNull?.let { argsList += listOf("--proxy", it) }
    argsList += listOf("--output", providers.gradleProperty("pairedRewardOutput").orNull ?: "build/research/paired-reward-shadow")
    argsList += listOf("--minutes", providers.gradleProperty("pairedRewardMinutes").orNull ?: "60")
    argsList += listOf("--max-markets", providers.gradleProperty("pairedRewardMaxMarkets").orNull ?: "7")
    argsList += listOf("--discovery-markets", providers.gradleProperty("pairedRewardDiscoveryMarkets").orNull ?: "1000")
    argsList += listOf("--discovery-pages", providers.gradleProperty("pairedRewardDiscoveryPages").orNull ?: "10")
    argsList += listOf("--min-volume", providers.gradleProperty("pairedRewardMinVolume").orNull ?: "1000")
    argsList += listOf("--min-price", providers.gradleProperty("pairedRewardMinPrice").orNull ?: "0.90")
    argsList += listOf("--max-price", providers.gradleProperty("pairedRewardMaxPrice").orNull ?: "0.955")
    argsList += listOf("--min-remaining-minutes", providers.gradleProperty("pairedRewardMinRemainingMinutes").orNull ?: "1440")
    argsList += listOf("--max-remaining-minutes", providers.gradleProperty("pairedRewardMaxRemainingMinutes").orNull ?: "43200")
    argsList += listOf("--shares", providers.gradleProperty("pairedRewardShares").orNull ?: "0")
    argsList += listOf("--improve-ticks", providers.gradleProperty("pairedRewardImproveTicks").orNull ?: "0")
    argsList += listOf("--max-pair-cost", providers.gradleProperty("pairedRewardMaxPairCost").orNull ?: "1.0")
    argsList += listOf("--max-hedge-pair-cost", providers.gradleProperty("pairedRewardMaxHedgePairCost").orNull ?: "1.0")
    argsList += listOf("--requote-seconds", providers.gradleProperty("pairedRewardRequoteSeconds").orNull ?: "120")
    argsList += listOf("--hedge-grace-ms", providers.gradleProperty("pairedRewardHedgeGraceMs").orNull ?: "5000")
    argsList += listOf("--hedge-slippage-cents", providers.gradleProperty("pairedRewardHedgeSlippageCents").orNull ?: "0.2")
    argsList += listOf("--taker-fee-rate", providers.gradleProperty("pairedRewardTakerFeeRate").orNull ?: "0.04")
    argsList += listOf("--fill-volume-factor", providers.gradleProperty("pairedRewardFillVolumeFactor").orNull ?: "1")
    argsList += listOf("--snapshot-seconds", providers.gradleProperty("pairedRewardSnapshotSeconds").orNull ?: "10")
    argsList += listOf("--poll-seconds", providers.gradleProperty("pairedRewardPollSeconds").orNull ?: "1")
    args(argsList)
}