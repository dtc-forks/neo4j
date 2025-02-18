/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.neo4j.common.EntityType
import org.neo4j.configuration.Config
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.cypher.internal.ast.AllIndexes
import org.neo4j.cypher.internal.ast.FulltextIndexes
import org.neo4j.cypher.internal.ast.LookupIndexes
import org.neo4j.cypher.internal.ast.PointIndexes
import org.neo4j.cypher.internal.ast.RangeIndexes
import org.neo4j.cypher.internal.ast.ShowIndexesClause
import org.neo4j.cypher.internal.ast.TextIndexes
import org.neo4j.cypher.internal.runtime.ConstraintInfo
import org.neo4j.cypher.internal.runtime.IndexInfo
import org.neo4j.cypher.internal.runtime.IndexStatus
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.graphdb.schema.IndexSettingImpl.FULLTEXT_ANALYZER
import org.neo4j.graphdb.schema.IndexSettingImpl.FULLTEXT_EVENTUALLY_CONSISTENT
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_CARTESIAN_3D_MAX
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_CARTESIAN_3D_MIN
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_CARTESIAN_MAX
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_CARTESIAN_MIN
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_WGS84_3D_MAX
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_WGS84_3D_MIN
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_WGS84_MAX
import org.neo4j.graphdb.schema.IndexSettingImpl.SPATIAL_WGS84_MIN
import org.neo4j.internal.schema.IndexConfig
import org.neo4j.internal.schema.IndexPrototype
import org.neo4j.internal.schema.IndexType
import org.neo4j.internal.schema.SchemaDescriptors
import org.neo4j.internal.schema.constraints.ConstraintDescriptorFactory
import org.neo4j.kernel.api.impl.fulltext.analyzer.providers.StandardNoStopWords
import org.neo4j.kernel.api.impl.fulltext.analyzer.providers.UrlOrEmail
import org.neo4j.kernel.api.impl.schema.TextIndexProvider
import org.neo4j.kernel.api.impl.schema.trigram.TrigramIndexProvider
import org.neo4j.kernel.api.index.IndexUsageStats
import org.neo4j.kernel.impl.index.schema.FulltextIndexProviderFactory
import org.neo4j.kernel.impl.index.schema.PointIndexProvider
import org.neo4j.kernel.impl.index.schema.RangeIndexProvider
import org.neo4j.kernel.impl.index.schema.TokenIndexProvider
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.VirtualValues

import java.time.Instant
import java.time.OffsetDateTime

class ShowIndexesCommandTest extends ShowCommandTestBase {

  private val defaultColumns =
    ShowIndexesClause(AllIndexes, brief = false, verbose = false, None, hasYield = false)(InputPosition.NONE)
      .unfilteredColumns
      .columns

  private val allColumns =
    ShowIndexesClause(AllIndexes, brief = false, verbose = false, None, hasYield = true)(InputPosition.NONE)
      .unfilteredColumns
      .columns

  private val nodeIndexInfo = IndexInfo(IndexStatus("ONLINE", "", 100.0, None), List(label), List(prop))
  private val relIndexInfo = IndexInfo(IndexStatus("ONLINE", "", 100.0, None), List(relType), List(prop))
  private val rangeProvider = RangeIndexProvider.DESCRIPTOR.name()
  private val lookupProvider = TokenIndexProvider.DESCRIPTOR.name()
  private val pointProvider = PointIndexProvider.DESCRIPTOR.name()
  private val textProvider = TrigramIndexProvider.DESCRIPTOR.name()
  private val oldTextProvider = TextIndexProvider.DESCRIPTOR.name()
  private val fulltextProvider = FulltextIndexProviderFactory.DESCRIPTOR.name()

  private val cartesianMin = SPATIAL_CARTESIAN_MIN.getSettingName
  private val cartesianMax = SPATIAL_CARTESIAN_MAX.getSettingName
  private val cartesian3dMin = SPATIAL_CARTESIAN_3D_MIN.getSettingName
  private val cartesian3dMax = SPATIAL_CARTESIAN_3D_MAX.getSettingName
  private val wgsMin = SPATIAL_WGS84_MIN.getSettingName
  private val wgsMax = SPATIAL_WGS84_MAX.getSettingName
  private val wgs3dMin = SPATIAL_WGS84_3D_MIN.getSettingName
  private val wgs3dMax = SPATIAL_WGS84_3D_MAX.getSettingName

  private val nodePointConfigMap =
    VirtualValues.map(
      Array(cartesian3dMax, cartesian3dMin, cartesianMax, cartesianMin, wgs3dMax, wgs3dMin, wgsMax, wgsMin),
      Array(
        Values.doubleArray(Array(1000000.0, 1000000.0, 1000000.0)),
        Values.doubleArray(Array(-1000000.0, -1000000.0, -1000000.0)),
        Values.doubleArray(Array(1000000.0, 1000000.0)),
        Values.doubleArray(Array(-1000000.0, -1000000.0)),
        Values.doubleArray(Array(180.0, 90.0, 1000000.0)),
        Values.doubleArray(Array(-180.0, -90.0, -1000000.0)),
        Values.doubleArray(Array(180.0, 90.0)),
        Values.doubleArray(Array(-180.0, -90.0))
      )
    )

  private val nodePointConfigMapString =
    s"{`$cartesian3dMax`: [1000000.0, 1000000.0, 1000000.0]," +
      s"`$cartesian3dMin`: [-1000000.0, -1000000.0, -1000000.0]," +
      s"`$cartesianMax`: [1000000.0, 1000000.0]," +
      s"`$cartesianMin`: [-1000000.0, -1000000.0]," +
      s"`$wgs3dMax`: [180.0, 90.0, 1000000.0]," +
      s"`$wgs3dMin`: [-180.0, -90.0, -1000000.0]," +
      s"`$wgsMax`: [180.0, 90.0]," +
      s"`$wgsMin`: [-180.0, -90.0]}"

  private val relPointConfigMap =
    VirtualValues.map(
      Array(cartesian3dMax, cartesian3dMin, cartesianMax, cartesianMin, wgs3dMax, wgs3dMin, wgsMax, wgsMin),
      Array(
        Values.doubleArray(Array(100.0, 100.0, 100.0)),
        Values.doubleArray(Array(-100.0, -100.0, -100.0)),
        Values.doubleArray(Array(100.0, 100.0)),
        Values.doubleArray(Array(-100.0, -100.0)),
        Values.doubleArray(Array(18.0, 9.0, 100.0)),
        Values.doubleArray(Array(-18.0, -9.0, -100.0)),
        Values.doubleArray(Array(18.0, 9.0)),
        Values.doubleArray(Array(-18.0, -9.0))
      )
    )

  private val relPointConfigMapString =
    s"{`$cartesian3dMax`: [100.0, 100.0, 100.0]," +
      s"`$cartesian3dMin`: [-100.0, -100.0, -100.0]," +
      s"`$cartesianMax`: [100.0, 100.0]," +
      s"`$cartesianMin`: [-100.0, -100.0]," +
      s"`$wgs3dMax`: [18.0, 9.0, 100.0]," +
      s"`$wgs3dMin`: [-18.0, -9.0, -100.0]," +
      s"`$wgsMax`: [18.0, 9.0]," +
      s"`$wgsMin`: [-18.0, -9.0]}"

  private val fulltextAnalyzer = FULLTEXT_ANALYZER.getSettingName
  private val fulltextAnalyzerName = StandardNoStopWords.ANALYZER_NAME
  private val fulltextAnalyzerName2 = new UrlOrEmail().getName
  private val fulltextEventuallyConsistent = FULLTEXT_EVENTUALLY_CONSISTENT.getSettingName

  private val nodeFulltextConfigMap = VirtualValues.map(
    Array(fulltextAnalyzer, fulltextEventuallyConsistent),
    Array(Values.stringValue(fulltextAnalyzerName), Values.FALSE)
  )

  private val nodeFulltextConfigMapString =
    s"{`$fulltextAnalyzer`: '$fulltextAnalyzerName',`$fulltextEventuallyConsistent`: false}"

  private val relFulltextConfigMap = VirtualValues.map(
    Array(fulltextAnalyzer, fulltextEventuallyConsistent),
    Array(Values.stringValue(fulltextAnalyzerName2), Values.TRUE)
  )

  private val relFulltextConfigMapString =
    s"{`$fulltextAnalyzer`: '$fulltextAnalyzerName2',`$fulltextEventuallyConsistent`: true}"

  private val rangeNodeIndexDescriptor =
    IndexPrototype.forSchema(labelDescriptor, RangeIndexProvider.DESCRIPTOR).withName("index0").materialise(0)

  private val rangeRelIndexDescriptor =
    IndexPrototype.forSchema(relTypeDescriptor, RangeIndexProvider.DESCRIPTOR).withName("index1").materialise(1)

  private val lookupNodeIndexDescriptor =
    IndexPrototype.forSchema(SchemaDescriptors.forAnyEntityTokens(EntityType.NODE), TokenIndexProvider.DESCRIPTOR)
      .withIndexType(IndexType.LOOKUP)
      .withName("index2")
      .materialise(2)

  private val lookupRelIndexDescriptor =
    IndexPrototype.forSchema(
      SchemaDescriptors.forAnyEntityTokens(EntityType.RELATIONSHIP),
      TokenIndexProvider.DESCRIPTOR
    )
      .withIndexType(IndexType.LOOKUP)
      .withName("index3")
      .materialise(3)

  private val pointNodeIndexDescriptor =
    IndexPrototype.forSchema(labelDescriptor, PointIndexProvider.DESCRIPTOR)
      .withIndexType(IndexType.POINT)
      .withName("index4")
      .withIndexConfig(
        IndexConfig.`with`(cartesian3dMax, Values.doubleArray(Array(1000000.0, 1000000.0, 1000000.0)))
          .withIfAbsent(cartesian3dMin, Values.doubleArray(Array(-1000000.0, -1000000.0, -1000000.0)))
          .withIfAbsent(cartesianMax, Values.doubleArray(Array(1000000.0, 1000000.0)))
          .withIfAbsent(cartesianMin, Values.doubleArray(Array(-1000000.0, -1000000.0)))
          .withIfAbsent(wgs3dMax, Values.doubleArray(Array(180.0, 90.0, 1000000.0)))
          .withIfAbsent(wgs3dMin, Values.doubleArray(Array(-180.0, -90.0, -1000000.0)))
          .withIfAbsent(wgsMax, Values.doubleArray(Array(180.0, 90.0)))
          .withIfAbsent(wgsMin, Values.doubleArray(Array(-180.0, -90.0)))
      )
      .materialise(4)

  private val pointRelIndexDescriptor =
    IndexPrototype.forSchema(relTypeDescriptor, PointIndexProvider.DESCRIPTOR)
      .withIndexType(IndexType.POINT)
      .withName("index5")
      .withIndexConfig(
        IndexConfig.`with`(cartesian3dMax, Values.doubleArray(Array(100.0, 100.0, 100.0)))
          .withIfAbsent(cartesian3dMin, Values.doubleArray(Array(-100.0, -100.0, -100.0)))
          .withIfAbsent(cartesianMax, Values.doubleArray(Array(100.0, 100.0)))
          .withIfAbsent(cartesianMin, Values.doubleArray(Array(-100.0, -100.0)))
          .withIfAbsent(wgs3dMax, Values.doubleArray(Array(18.0, 9.0, 100.0)))
          .withIfAbsent(wgs3dMin, Values.doubleArray(Array(-18.0, -9.0, -100.0)))
          .withIfAbsent(wgsMax, Values.doubleArray(Array(18.0, 9.0)))
          .withIfAbsent(wgsMin, Values.doubleArray(Array(-18.0, -9.0)))
      )
      .materialise(5)

  private val textNodeIndexDescriptor =
    IndexPrototype.forSchema(labelDescriptor, TrigramIndexProvider.DESCRIPTOR)
      .withIndexType(IndexType.TEXT)
      .withName("index6")
      .materialise(6)

  private val textRelIndexDescriptor =
    IndexPrototype.forSchema(relTypeDescriptor, TextIndexProvider.DESCRIPTOR)
      .withIndexType(IndexType.TEXT)
      .withName("index7")
      .materialise(7)

  private val fulltextNodeIndexDescriptor =
    IndexPrototype.forSchema(
      SchemaDescriptors.fulltext(EntityType.NODE, Array(0), Array(0)),
      FulltextIndexProviderFactory.DESCRIPTOR
    )
      .withIndexType(IndexType.FULLTEXT)
      .withName("index8")
      .withIndexConfig(
        IndexConfig.`with`(fulltextAnalyzer, Values.stringValue(fulltextAnalyzerName))
          .withIfAbsent(fulltextEventuallyConsistent, Values.FALSE)
      )
      .materialise(8)

  private val fulltextRelIndexDescriptor =
    IndexPrototype.forSchema(
      SchemaDescriptors.fulltext(EntityType.RELATIONSHIP, Array(0), Array(0)),
      FulltextIndexProviderFactory.DESCRIPTOR
    )
      .withIndexType(IndexType.FULLTEXT)
      .withName("index9")
      .withIndexConfig(
        IndexConfig.`with`(fulltextAnalyzer, Values.stringValue(fulltextAnalyzerName2))
          .withIfAbsent(fulltextEventuallyConsistent, Values.TRUE)
      )
      .materialise(9)

  private val config = Config.defaults()

  override def beforeEach(): Unit = {
    super.beforeEach()

    // Defaults:
    when(ctx.getConfig).thenReturn(config)
    when(ctx.getAllConstraints()).thenReturn(Map.empty)

    // No statistics, most tests won't care about them but don't want null-pointers
    val statistics = mock[IndexUsageStats]
    when(statistics.trackedSince()).thenReturn(0)
    when(statistics.lastRead()).thenReturn(0)
    when(statistics.readCount()).thenReturn(0)
    when(ctx.getIndexUsageStatistics(any())).thenReturn(statistics)
  }

  private def getTemporalValueFromMs(time: Long) =
    Values.temporalValue(OffsetDateTime.ofInstant(
      Instant.ofEpochMilli(time),
      config.get(GraphDatabaseSettings.db_timezone).getZoneId
    ).toZonedDateTime)

  // Only checks the given parameters
  private def checkResult(
    resultMap: Map[String, AnyValue],
    id: Option[Long] = None,
    name: Option[String] = None,
    state: Option[String] = None,
    population: Option[Float] = None,
    indexType: Option[String] = None,
    entityType: Option[String] = None,
    labelsOrTypes: Option[List[String]] = None,
    properties: Option[List[String]] = None,
    provider: Option[String] = None,
    constraint: Option[String] = None,
    lastRead: Option[AnyValue] = None,
    readCount: Option[AnyValue] = None,
    trackedSince: Option[AnyValue] = None,
    options: Option[Map[String, AnyValue]] = None,
    failureMessage: Option[String] = None,
    createStatement: Option[String] = None
  ): Unit = {
    id.foreach(expected => resultMap("id") should be(Values.longValue(expected)))
    name.foreach(expected => resultMap("name") should be(Values.stringValue(expected)))
    state.foreach(expected => resultMap("state") should be(Values.stringValue(expected)))
    population.foreach(expected => resultMap("populationPercent") should be(Values.floatValue(expected)))
    indexType.foreach(expected => resultMap("type") should be(Values.stringValue(expected)))
    entityType.foreach(expected => resultMap("entityType") should be(Values.stringValue(expected)))
    labelsOrTypes.foreach(maybeExpected => {
      val expected =
        if (maybeExpected != null) VirtualValues.list(maybeExpected.map(Values.stringValue): _*)
        else Values.NO_VALUE

      resultMap("labelsOrTypes") should be(expected)
    })
    properties.foreach(maybeExpected => {
      val expected =
        if (maybeExpected != null) VirtualValues.list(maybeExpected.map(Values.stringValue): _*)
        else Values.NO_VALUE

      resultMap("properties") should be(expected)
    })
    provider.foreach(expected => resultMap("indexProvider") should be(Values.stringValue(expected)))
    constraint.foreach(expected => resultMap("owningConstraint") should be(Values.stringOrNoValue(expected)))
    lastRead.foreach(expected => resultMap("lastRead") should be(expected))
    readCount.foreach(expected => resultMap("readCount") should be(expected))
    trackedSince.foreach(expected => resultMap("trackedSince") should be(expected))
    options.foreach(expected =>
      resultMap("options") should be(VirtualValues.map(expected.view.keys.toArray, expected.view.values.toArray))
    )
    failureMessage.foreach(expected => resultMap("failureMessage") should be(Values.stringValue(expected)))
    createStatement.foreach(expected => resultMap("createStatement") should be(Values.stringValue(expected)))
  }

  // Tests

  test("show indexes should give back correct default values") {
    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = false, defaultColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      id = 0L,
      name = "index0",
      state = "ONLINE",
      population = 100.0f,
      indexType = "RANGE",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = rangeProvider,
      constraint = Some(null),
      lastRead = Values.NO_VALUE,
      readCount = Values.NO_VALUE
    )
    checkResult(
      result.last,
      id = 1L,
      name = "index1",
      state = "ONLINE",
      population = 100.0f,
      indexType = "RANGE",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = rangeProvider,
      constraint = Some(null),
      lastRead = Values.NO_VALUE,
      readCount = Values.NO_VALUE
    )
    // confirm no verbose columns:
    result.foreach(res => {
      res.keys.toList should contain noElementsOf List("trackedSince", "options", "failureMessage", "createStatement")
    })
  }

  test("show indexes should give back correct full values") {
    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      id = 0L,
      name = "index0",
      state = "ONLINE",
      population = 100.0f,
      indexType = "RANGE",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = rangeProvider,
      constraint = Some(null),
      lastRead = Values.NO_VALUE,
      readCount = Values.NO_VALUE,
      trackedSince = Values.NO_VALUE,
      options = Map("indexProvider" -> Values.stringValue(rangeProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      failureMessage = "",
      createStatement = s"CREATE RANGE INDEX `index0` FOR (n:`$label`) ON (n.`$prop`)"
    )
    checkResult(
      result.last,
      id = 1L,
      name = "index1",
      state = "ONLINE",
      population = 100.0f,
      indexType = "RANGE",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = rangeProvider,
      constraint = Some(null),
      lastRead = Values.NO_VALUE,
      readCount = Values.NO_VALUE,
      trackedSince = Values.NO_VALUE,
      options = Map("indexProvider" -> Values.stringValue(rangeProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      failureMessage = "",
      createStatement = s"CREATE RANGE INDEX `index1` FOR ()-[r:`$relType`]-() ON (r.`$prop`)"
    )
  }

  test("show indexes should give back correct index info") {
    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> IndexInfo(IndexStatus("POPULATING", "", 70.0, None), List(label), List(prop)),
      rangeRelIndexDescriptor -> IndexInfo(
        IndexStatus("NOT FOUND", "Index not found. It might have been concurrently dropped.", 0.0, None),
        List(relType),
        List(prop)
      )
    ))

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      state = "POPULATING",
      population = 70.0f,
      labelsOrTypes = List(label),
      properties = List(prop),
      failureMessage = ""
    )
    checkResult(
      result.last,
      state = "NOT FOUND",
      population = 0.0f,
      labelsOrTypes = List(relType),
      properties = List(prop),
      failureMessage = "Index not found. It might have been concurrently dropped."
    )
  }

  test("show indexes should show indexes backing constraints") {
    // Index and constraint descriptors
    val nodeIndexDescriptor =
      IndexPrototype.uniqueForSchema(labelDescriptor, RangeIndexProvider.DESCRIPTOR)
        .withName("index0")
        .materialise(0)
        .withOwningConstraintId(1)

    val relIndexDescriptor =
      IndexPrototype.uniqueForSchema(relTypeDescriptor, RangeIndexProvider.DESCRIPTOR)
        .withName("index1")
        .materialise(2)
        .withOwningConstraintId(3)

    val nodeConstraintDescriptor =
      ConstraintDescriptorFactory.uniqueForSchema(nodeIndexDescriptor.schema(), IndexType.RANGE)
        .withName("index0")
        .withOwnedIndexId(0)
        .withId(1)

    val relConstraintDescriptor =
      ConstraintDescriptorFactory.keyForSchema(relIndexDescriptor.schema(), IndexType.RANGE)
        .withName("index1")
        .withOwnedIndexId(2)
        .withId(3)

    // Override returned constraints:
    when(ctx.getAllConstraints()).thenReturn(Map(
      nodeConstraintDescriptor -> ConstraintInfo(List(label), List(prop), Some(nodeIndexDescriptor)),
      relConstraintDescriptor -> ConstraintInfo(List(relType), List(prop), Some(relIndexDescriptor))
    ))

    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(
      nodeIndexDescriptor -> IndexInfo(
        IndexStatus("ONLINE", "", 100.0, Some(nodeConstraintDescriptor)),
        List(label),
        List(prop)
      ),
      relIndexDescriptor -> IndexInfo(
        IndexStatus("ONLINE", "", 100.0, Some(relConstraintDescriptor)),
        List(relType),
        List(prop)
      )
    ))

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      name = "index0",
      constraint = Some("index0"),
      createStatement =
        s"CREATE CONSTRAINT `index0` FOR (n:`$label`) REQUIRE (n.`$prop`) IS UNIQUE " +
          s"OPTIONS {indexConfig: {}, indexProvider: '$rangeProvider'}"
    )
    checkResult(
      result.last,
      name = "index1",
      constraint = Some("index1"),
      createStatement =
        s"CREATE CONSTRAINT `index1` FOR ()-[r:`$relType`]-() REQUIRE (r.`$prop`) IS RELATIONSHIP KEY " +
          s"OPTIONS {indexConfig: {}, indexProvider: '$rangeProvider'}"
    )
  }

  test("show indexes should return the indexes sorted on name") {
    // Set-up which indexes to return, ordered descending by name:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeRelIndexDescriptor -> relIndexInfo,
      rangeNodeIndexDescriptor -> nodeIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = false, defaultColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(result.head, name = "index0")
    checkResult(result.last, name = "index1")
  }

  test("show indexes should give back nulls when no statistics available") {
    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(rangeNodeIndexDescriptor -> nodeIndexInfo))

    // override statistics to be explicit about what we test (even if it's the default values XD)
    val statistics = mock[IndexUsageStats]
    when(statistics.trackedSince()).thenReturn(0)
    when(statistics.lastRead()).thenReturn(0)
    when(statistics.readCount()).thenReturn(0)
    when(ctx.getIndexUsageStatistics(any())).thenReturn(statistics)

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 1
    checkResult(result.head, trackedSince = Values.NO_VALUE, lastRead = Values.NO_VALUE, readCount = Values.NO_VALUE)
  }

  test("show indexes should give back correct statistic values for unused indexes") {
    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(rangeNodeIndexDescriptor -> nodeIndexInfo))

    // override as we want other statistics
    val trackedSinceMs = 1
    val statistics = mock[IndexUsageStats]
    when(statistics.trackedSince()).thenReturn(trackedSinceMs)
    when(statistics.lastRead()).thenReturn(0)
    when(statistics.readCount()).thenReturn(0)
    when(ctx.getIndexUsageStatistics(any())).thenReturn(statistics)

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 1
    checkResult(
      result.head,
      trackedSince = getTemporalValueFromMs(trackedSinceMs),
      lastRead = Values.NO_VALUE,
      readCount = Values.longValue(0)
    )
  }

  test("show indexes should give back correct statistic values for used indexes") {
    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(rangeNodeIndexDescriptor -> nodeIndexInfo))

    // override as we want other statistics
    val trackedSinceMs = 1
    val lastReadMs = 5
    val readCount = 3
    val statistics = mock[IndexUsageStats]
    when(statistics.trackedSince()).thenReturn(trackedSinceMs)
    when(statistics.lastRead()).thenReturn(lastReadMs)
    when(statistics.readCount()).thenReturn(readCount)
    when(ctx.getIndexUsageStatistics(any())).thenReturn(statistics)

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 1
    checkResult(
      result.head,
      trackedSince = getTemporalValueFromMs(trackedSinceMs),
      lastRead = getTemporalValueFromMs(lastReadMs),
      readCount = Values.longValue(readCount)
    )
  }

  test("show indexes should give back correct default statistic values for used indexes without yield") {
    // Set-up which indexes to return:
    when(ctx.getAllIndexes()).thenReturn(Map(rangeNodeIndexDescriptor -> nodeIndexInfo))

    // override as we want other statistics
    val trackedSinceMs = 1
    val lastReadMs = 5
    val readCount = 3
    val statistics = mock[IndexUsageStats]
    when(statistics.trackedSince()).thenReturn(trackedSinceMs)
    when(statistics.lastRead()).thenReturn(lastReadMs)
    when(statistics.readCount()).thenReturn(readCount)
    when(ctx.getIndexUsageStatistics(any())).thenReturn(statistics)

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = false, defaultColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 1
    result.head.get("trackedSince") should be(None) // not a default column
    checkResult(
      result.head,
      lastRead = getTemporalValueFromMs(lastReadMs),
      readCount = Values.longValue(readCount)
    )
  }

  test("show indexes should show all index types") {
    // Set-up which indexes the context returns:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo,
      lookupNodeIndexDescriptor -> nodeIndexInfo,
      lookupRelIndexDescriptor -> relIndexInfo,
      pointNodeIndexDescriptor -> nodeIndexInfo,
      pointRelIndexDescriptor -> relIndexInfo,
      textNodeIndexDescriptor -> nodeIndexInfo,
      textRelIndexDescriptor -> relIndexInfo,
      fulltextNodeIndexDescriptor -> nodeIndexInfo,
      fulltextRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(AllIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 10
    checkResult(
      result.head,
      name = "index0",
      indexType = "RANGE",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = rangeProvider,
      options = Map("indexProvider" -> Values.stringValue(rangeProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE RANGE INDEX `index0` FOR (n:`$label`) ON (n.`$prop`)"
    )
    checkResult(
      result(1),
      name = "index1",
      indexType = "RANGE",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = rangeProvider,
      options = Map("indexProvider" -> Values.stringValue(rangeProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE RANGE INDEX `index1` FOR ()-[r:`$relType`]-() ON (r.`$prop`)"
    )
    checkResult(
      result(2),
      name = "index2",
      indexType = "LOOKUP",
      entityType = "NODE",
      labelsOrTypes = Some(null),
      properties = Some(null),
      provider = lookupProvider,
      options = Map("indexProvider" -> Values.stringValue(lookupProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = "CREATE LOOKUP INDEX `index2` FOR (n) ON EACH labels(n)"
    )
    checkResult(
      result(3),
      name = "index3",
      indexType = "LOOKUP",
      entityType = "RELATIONSHIP",
      labelsOrTypes = Some(null),
      properties = Some(null),
      provider = lookupProvider,
      options = Map("indexProvider" -> Values.stringValue(lookupProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = "CREATE LOOKUP INDEX `index3` FOR ()-[r]-() ON EACH type(r)"
    )
    checkResult(
      result(4),
      name = "index4",
      indexType = "POINT",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = pointProvider,
      options = Map("indexProvider" -> Values.stringValue(pointProvider), "indexConfig" -> nodePointConfigMap),
      createStatement = s"CREATE POINT INDEX `index4` FOR (n:`$label`) ON (n.`$prop`) " +
        s"OPTIONS {indexConfig: $nodePointConfigMapString, indexProvider: '$pointProvider'}"
    )
    checkResult(
      result(5),
      name = "index5",
      indexType = "POINT",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = pointProvider,
      options = Map("indexProvider" -> Values.stringValue(pointProvider), "indexConfig" -> relPointConfigMap),
      createStatement = s"CREATE POINT INDEX `index5` FOR ()-[r:`$relType`]-() ON (r.`$prop`) " +
        s"OPTIONS {indexConfig: $relPointConfigMapString, indexProvider: '$pointProvider'}"
    )
    checkResult(
      result(6),
      name = "index6",
      indexType = "TEXT",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = textProvider,
      options = Map("indexProvider" -> Values.stringValue(textProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE TEXT INDEX `index6` FOR (n:`$label`) ON (n.`$prop`) " +
        s"OPTIONS {indexConfig: {}, indexProvider: '$textProvider'}"
    )
    checkResult(
      result(7),
      name = "index7",
      indexType = "TEXT",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = oldTextProvider,
      options = Map("indexProvider" -> Values.stringValue(oldTextProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE TEXT INDEX `index7` FOR ()-[r:`$relType`]-() ON (r.`$prop`) " +
        s"OPTIONS {indexConfig: {}, indexProvider: '$oldTextProvider'}"
    )
    checkResult(
      result(8),
      name = "index8",
      indexType = "FULLTEXT",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = fulltextProvider,
      options = Map("indexProvider" -> Values.stringValue(fulltextProvider), "indexConfig" -> nodeFulltextConfigMap),
      createStatement = s"CREATE FULLTEXT INDEX `index8` FOR (n:`$label`) ON EACH [n.`$prop`] " +
        s"OPTIONS {indexConfig: $nodeFulltextConfigMapString, indexProvider: '$fulltextProvider'}"
    )
    checkResult(
      result(9),
      name = "index9",
      indexType = "FULLTEXT",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = fulltextProvider,
      options = Map("indexProvider" -> Values.stringValue(fulltextProvider), "indexConfig" -> relFulltextConfigMap),
      createStatement = s"CREATE FULLTEXT INDEX `index9` FOR ()-[r:`$relType`]-() ON EACH [r.`$prop`] " +
        s"OPTIONS {indexConfig: $relFulltextConfigMapString, indexProvider: '$fulltextProvider'}"
    )
  }

  test("show range indexes should only show range indexes") {
    // Set-up which indexes the context returns:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo,
      lookupNodeIndexDescriptor -> nodeIndexInfo,
      lookupRelIndexDescriptor -> relIndexInfo,
      pointNodeIndexDescriptor -> nodeIndexInfo,
      pointRelIndexDescriptor -> relIndexInfo,
      textNodeIndexDescriptor -> nodeIndexInfo,
      textRelIndexDescriptor -> relIndexInfo,
      fulltextNodeIndexDescriptor -> nodeIndexInfo,
      fulltextRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(RangeIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      name = "index0",
      indexType = "RANGE",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = rangeProvider,
      options = Map("indexProvider" -> Values.stringValue(rangeProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE RANGE INDEX `index0` FOR (n:`$label`) ON (n.`$prop`)"
    )
    checkResult(
      result.last,
      name = "index1",
      indexType = "RANGE",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = rangeProvider,
      options = Map("indexProvider" -> Values.stringValue(rangeProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE RANGE INDEX `index1` FOR ()-[r:`$relType`]-() ON (r.`$prop`)"
    )
  }

  test("show lookup indexes should only show lookup indexes") {
    // Set-up which indexes the context returns:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo,
      lookupNodeIndexDescriptor -> nodeIndexInfo,
      lookupRelIndexDescriptor -> relIndexInfo,
      pointNodeIndexDescriptor -> nodeIndexInfo,
      pointRelIndexDescriptor -> relIndexInfo,
      textNodeIndexDescriptor -> nodeIndexInfo,
      textRelIndexDescriptor -> relIndexInfo,
      fulltextNodeIndexDescriptor -> nodeIndexInfo,
      fulltextRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(LookupIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      name = "index2",
      indexType = "LOOKUP",
      entityType = "NODE",
      labelsOrTypes = Some(null),
      properties = Some(null),
      provider = lookupProvider,
      options = Map("indexProvider" -> Values.stringValue(lookupProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = "CREATE LOOKUP INDEX `index2` FOR (n) ON EACH labels(n)"
    )
    checkResult(
      result.last,
      name = "index3",
      indexType = "LOOKUP",
      entityType = "RELATIONSHIP",
      labelsOrTypes = Some(null),
      properties = Some(null),
      provider = lookupProvider,
      options = Map("indexProvider" -> Values.stringValue(lookupProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = "CREATE LOOKUP INDEX `index3` FOR ()-[r]-() ON EACH type(r)"
    )
  }

  test("show point indexes should only show point indexes") {
    // Set-up which indexes the context returns:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo,
      lookupNodeIndexDescriptor -> nodeIndexInfo,
      lookupRelIndexDescriptor -> relIndexInfo,
      pointNodeIndexDescriptor -> nodeIndexInfo,
      pointRelIndexDescriptor -> relIndexInfo,
      textNodeIndexDescriptor -> nodeIndexInfo,
      textRelIndexDescriptor -> relIndexInfo,
      fulltextNodeIndexDescriptor -> nodeIndexInfo,
      fulltextRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(PointIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      name = "index4",
      indexType = "POINT",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = pointProvider,
      options = Map("indexProvider" -> Values.stringValue(pointProvider), "indexConfig" -> nodePointConfigMap),
      createStatement = s"CREATE POINT INDEX `index4` FOR (n:`$label`) ON (n.`$prop`) " +
        s"OPTIONS {indexConfig: $nodePointConfigMapString, indexProvider: '$pointProvider'}"
    )
    checkResult(
      result.last,
      name = "index5",
      indexType = "POINT",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = pointProvider,
      options = Map("indexProvider" -> Values.stringValue(pointProvider), "indexConfig" -> relPointConfigMap),
      createStatement = s"CREATE POINT INDEX `index5` FOR ()-[r:`$relType`]-() ON (r.`$prop`) " +
        s"OPTIONS {indexConfig: $relPointConfigMapString, indexProvider: '$pointProvider'}"
    )
  }

  test("show text indexes should only show text indexes") {
    // Set-up which indexes the context returns:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo,
      lookupNodeIndexDescriptor -> nodeIndexInfo,
      lookupRelIndexDescriptor -> relIndexInfo,
      pointNodeIndexDescriptor -> nodeIndexInfo,
      pointRelIndexDescriptor -> relIndexInfo,
      textNodeIndexDescriptor -> nodeIndexInfo,
      textRelIndexDescriptor -> relIndexInfo,
      fulltextNodeIndexDescriptor -> nodeIndexInfo,
      fulltextRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(TextIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      name = "index6",
      indexType = "TEXT",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = textProvider,
      options = Map("indexProvider" -> Values.stringValue(textProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE TEXT INDEX `index6` FOR (n:`$label`) ON (n.`$prop`) " +
        s"OPTIONS {indexConfig: {}, indexProvider: '$textProvider'}"
    )
    checkResult(
      result.last,
      name = "index7",
      indexType = "TEXT",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = oldTextProvider,
      options = Map("indexProvider" -> Values.stringValue(oldTextProvider), "indexConfig" -> VirtualValues.EMPTY_MAP),
      createStatement = s"CREATE TEXT INDEX `index7` FOR ()-[r:`$relType`]-() ON (r.`$prop`) " +
        s"OPTIONS {indexConfig: {}, indexProvider: '$oldTextProvider'}"
    )
  }

  test("show fulltext indexes should only show fulltext indexes") {
    // Set-up which indexes the context returns:
    when(ctx.getAllIndexes()).thenReturn(Map(
      rangeNodeIndexDescriptor -> nodeIndexInfo,
      rangeRelIndexDescriptor -> relIndexInfo,
      lookupNodeIndexDescriptor -> nodeIndexInfo,
      lookupRelIndexDescriptor -> relIndexInfo,
      pointNodeIndexDescriptor -> nodeIndexInfo,
      pointRelIndexDescriptor -> relIndexInfo,
      textNodeIndexDescriptor -> nodeIndexInfo,
      textRelIndexDescriptor -> relIndexInfo,
      fulltextNodeIndexDescriptor -> nodeIndexInfo,
      fulltextRelIndexDescriptor -> relIndexInfo
    ))

    // When
    val showIndexes = ShowIndexesCommand(FulltextIndexes, verbose = true, allColumns)
    val result = showIndexes.originalNameRows(queryState, initialCypherRow).toList

    // Then
    result should have size 2
    checkResult(
      result.head,
      name = "index8",
      indexType = "FULLTEXT",
      entityType = "NODE",
      labelsOrTypes = List(label),
      properties = List(prop),
      provider = fulltextProvider,
      options = Map("indexProvider" -> Values.stringValue(fulltextProvider), "indexConfig" -> nodeFulltextConfigMap),
      createStatement = s"CREATE FULLTEXT INDEX `index8` FOR (n:`$label`) ON EACH [n.`$prop`] " +
        s"OPTIONS {indexConfig: $nodeFulltextConfigMapString, indexProvider: '$fulltextProvider'}"
    )
    checkResult(
      result.last,
      name = "index9",
      indexType = "FULLTEXT",
      entityType = "RELATIONSHIP",
      labelsOrTypes = List(relType),
      properties = List(prop),
      provider = fulltextProvider,
      options = Map("indexProvider" -> Values.stringValue(fulltextProvider), "indexConfig" -> relFulltextConfigMap),
      createStatement = s"CREATE FULLTEXT INDEX `index9` FOR ()-[r:`$relType`]-() ON EACH [r.`$prop`] " +
        s"OPTIONS {indexConfig: $relFulltextConfigMapString, indexProvider: '$fulltextProvider'}"
    )
  }
}
