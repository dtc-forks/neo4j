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
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.runtime.spec.Edition
import org.neo4j.cypher.internal.runtime.spec.LogicalQueryBuilder
import org.neo4j.cypher.internal.runtime.spec.RuntimeTestSuite
import org.neo4j.graphdb.Direction.INCOMING
import org.neo4j.graphdb.Direction.OUTGOING
import org.neo4j.graphdb.Label
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.Relationship
import org.neo4j.graphdb.RelationshipType

import java.util.Collections

import scala.collection.JavaConverters.asJavaIterableConverter
import scala.jdk.CollectionConverters.IterableHasAsScala

//noinspection ZeroIndexToHead
abstract class ProjectEndpointsTestBase[CONTEXT <: RuntimeContext](
  edition: Edition[CONTEXT],
  runtime: CypherRuntime[CONTEXT],
  protected val sizeHint: Int
) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("should project endpoints - OUTGOING - start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes) = given { bipartiteGraph(nNodes, "A", "B", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]->(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(a, b)
    }

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - INCOMING - start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes) = given { bipartiteGraph(nNodes, "A", "B", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)<-[r]-(y)", startInScope = true, endInScope = false)
      .expandAll("(x)<-[r]-()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(b, a)
    }

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - undirected - start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]-(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - undirected - start in scope with relationship types") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA]-(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(a, b)
    }

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - undirected - nothing in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes) = given { bipartiteGraph(nNodes, "A", "B", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x2", "y2")
      .projectEndpoints("(x2)-[r]-(y2)", startInScope = false, endInScope = false)
      .expandAll("(x)-[r]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected =
      for {
        a <- aNodes
        b <- bNodes
      } yield {
        Array(
          Array(a, b),
          Array(b, a)
        )
      }

    runtimeResult should beColumns("x2", "y2").withRows(expected.flatten)
  }

  test("should project endpoints - directed - nothing in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]->(y)", startInScope = false, endInScope = false)
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, aRels.map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(a, b)
    }

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - directed - nothing in scope - using input with nulls") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]->(y)", startInScope = false, endInScope = false)
      .input(relationships = Seq("r"))
      .build()

    val aRelsWithNulls = aRels.flatMap(r => Seq(null, r))
    val input = batchedInputValues(sizeHint / 8, aRelsWithNulls.map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(a, b)
    }

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - undirected - nothing in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]-(y)", startInScope = false, endInScope = false)
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, aRels.map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - undirected - nothing in scope - using input with relationship type") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, bRels) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA]-(y)", startInScope = false, endInScope = false)
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, (aRels ++ bRels).map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints with hash join under apply - directed - nothing in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, bRels) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .apply()
      .|.nodeHashJoin("x")
      .|.|.projectEndpoints("(x)-[r]->(y)", startInScope = false, endInScope = false)
      .|.|.argument("r")
      .|.projectEndpoints("(x)-[r]->(y)", startInScope = false, endInScope = false)
      .|.argument("r")
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, (aRels ++ bRels).map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(rowCount(expected.size))
  }

  test("should project endpoints with hash join under apply - undirected/directed - nothing in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, bRels) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .apply()
      .|.nodeHashJoin("x", "y")
      .|.|.projectEndpoints("(x)-[r]->(y)", startInScope = false, endInScope = false)
      .|.|.argument("r")
      .|.projectEndpoints("(x)-[r]-(y)", startInScope = false, endInScope = false)
      .|.argument("r")
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, (aRels ++ bRels).map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints with hash join under apply - directed/undirected - nothing in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, bRels) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .apply()
      .|.nodeHashJoin("x", "y")
      .|.|.projectEndpoints("(x)-[r]-(y)", startInScope = false, endInScope = false)
      .|.|.argument("r")
      .|.projectEndpoints("(x)-[r]->(y)", startInScope = false, endInScope = false)
      .|.argument("r")
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, (aRels ++ bRels).map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - chained directed - both in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, bRels) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]->(y)", startInScope = true, endInScope = true) // chained middle
      .projectEndpoints("(x)-[r]->(y)", startInScope = false, endInScope = false) // middle
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, (aRels ++ bRels).map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - chained undirected/outgoing - end in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, bRels) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(a)-[r]->(x)", startInScope = false, endInScope = true) // chained middle
      .projectEndpoints("(x)-[r]-(y)", startInScope = false, endInScope = false) // head
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, (aRels ++ bRels).map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - chained outgoing/undirected - start in scope - using input") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, aRels, bRels) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]-(y)", startInScope = true, endInScope = false) // head
      .projectEndpoints("(a)-[r]->(x)", startInScope = false, endInScope = false) // chained middle
      .input(relationships = Seq("r"), nullable = false)
      .build()

    val input = batchedInputValues(sizeHint / 8, (aRels ++ bRels).map(r => Array[Any](r)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    val expected = (for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(Array(a, b), Array(b, a))
    }).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("single relationship one hop loop start in scope") {
    // given
    val n = given {
      val n = runtimeTestSupport.tx.createNode()
      n.createRelationshipTo(n, RelationshipType.withName("1-HOP"))
      n
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .projectEndpoints("(x)-[r]-(y2)", startInScope = true, endInScope = false)
      .expandInto("(x)-[r]->(x)")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = Seq(Array(n))

    runtimeResult should beColumns("x").withRows(expected)
  }

  // ====================================================================
  // ==========================VAR LENGTH================================
  // ====================================================================

  test("should project endpoints - varlength - start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .projectEndpoints("(x)-[r*]-(y)", startInScope = true, endInScope = false)
      .expand("(x)<-[r*1..2]-()", projectedDir = SemanticDirection.INCOMING)
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- aNodes ++ bNodes
      oneStep =
        node.getRelationships(INCOMING).asScala.map(r =>
          Array[Any](node, Collections.singletonList(r), r.getOtherNode(node))
        )
      twoSteps = oneStep.flatMap {
        case Array(_, rs, b) =>
          val r = rs.asInstanceOf[java.util.List[Relationship]].get(0)
          b.asInstanceOf[Node].getRelationships(INCOMING).asScala.map(r2 =>
            Array[Any](node, java.util.List.of(r, r2), r2.getOtherNode(b.asInstanceOf[Node]))
          )
      }
    } yield oneStep ++ twoSteps).flatten

    runtimeResult should beColumns("x", "r", "y").withRows(expected)
  }

  test("should project endpoints - varlength directed, start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r*]->(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r*1..2]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- aNodes ++ bNodes
      oneStep: Iterable[Array[Node]] =
        node.getRelationships(OUTGOING).asScala.map(r => Array(node, r.getOtherNode(node)))
      twoSteps: Iterable[Array[Node]] = oneStep.flatMap {
        case Array(_, b) => b.getRelationships(OUTGOING).asScala.map(r => Array(node, r.getOtherNode(b)))
      }
    } yield oneStep ++ twoSteps).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength directed with type, start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA*]->(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r*1..2]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- aNodes
      oneStep: Iterable[Array[Node]] = node.getRelationships(OUTGOING, RelationshipType.withName("RA")).asScala.map(r =>
        Array(node, r.getOtherNode(node))
      )
    } yield oneStep).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength directed, nothing in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:*]->(y)", startInScope = false, endInScope = false)
      .expandAll("(n)-[r*1..2]->()")
      .allNodeScan("n")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- aNodes ++ bNodes
      oneStep: Iterable[Array[Node]] =
        node.getRelationships(OUTGOING).asScala.map(r => Array(node, r.getOtherNode(node)))
      twoSteps: Iterable[Array[Node]] = oneStep.flatMap {
        case Array(_, b) => b.getRelationships(OUTGOING).asScala.map(r => Array(node, r.getOtherNode(b)))
      }
    } yield oneStep ++ twoSteps).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength directed with type, nothing in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA*]->(y)", startInScope = false, endInScope = false)
      .expandAll("(n)-[r*1..2]->()")
      .allNodeScan("n")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- aNodes
      oneStep: Iterable[Array[Node]] = node.getRelationships(OUTGOING, RelationshipType.withName("RA")).asScala.map(r =>
        Array(node, r.getOtherNode(node))
      )
    } yield oneStep).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength directed, end in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:*]->(y)", startInScope = false, endInScope = true)
      .expand("(y)<-[r*1..2]-()", projectedDir = SemanticDirection.INCOMING)
      .allNodeScan("y")
      .build()

    // In the var expand, we have bound y to the first relationship in r.
    // In the project endpoints, we have bound y to the last relationship in r.
    // Moreover, the relationship list is directed in both cases.
    // The only relationship lists that satisfy the above requirements are of length 1.

    val oneHops =
      (for {
        a <- aNodes
        b <- bNodes
      } yield {
        Array(Array(a, b), Array(b, a))
      }).flatten

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = oneHops

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength directed with type, end in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (_, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA*]->(y)", startInScope = false, endInScope = true)
      .expand("(y)<-[r*1..2]-()", projectedDir = SemanticDirection.INCOMING)
      .allNodeScan("y")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- bNodes
      oneStep: Iterable[Array[Node]] = node.getRelationships(INCOMING, RelationshipType.withName("RA")).asScala.map(r =>
        Array(r.getOtherNode(node), node)
      )
    } yield oneStep).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength undirected, start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r*]-(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r*1..2]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- aNodes ++ bNodes
      oneStep: Iterable[Array[Node]] =
        node.getRelationships(OUTGOING).asScala.map(r => Array(node, r.getOtherNode(node)))
      twoSteps: Iterable[Array[Node]] = oneStep.flatMap {
        case Array(_, b) => b.getRelationships(OUTGOING).asScala.map(r => Array(node, r.getOtherNode(b)))
      }
    } yield oneStep ++ twoSteps).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength undirected with type, start in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA*]-(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r*1..2]->()")
      .allNodeScan("x")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (for {
      node <- aNodes
      oneStep: Iterable[Array[Node]] = node.getRelationships(OUTGOING, RelationshipType.withName("RA")).asScala.map(r =>
        Array(node, r.getOtherNode(node))
      )
    } yield oneStep).flatten

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength undirected, nothing in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:*]-(y)", startInScope = false, endInScope = false)
      .expandAll("(n)-[r*1..2]->()")
      .allNodeScan("n")
      .build()

    // ONE HOPS:
    //
    // The expand will match all pairs (a, b) and (b, a)
    // The projectEndpoints will project these in both the original direction, and it's reverse. Thus it will produce
    // duplicates of each pair: (a, b), (b, a), (b, a), (a, b)
    //
    // TWO HOPS:
    //
    // The expand will produce all triplets (a1, b, a2) and (b1, a, b2). We have two cases:
    //
    //  - case a1 != a2: In these cases project endpoints will not be able to consider the relationships in reverse,
    //                   since it would yield an invalid path. Hence it will return (a1, a2)
    //
    //  - case a1 == a2: In these cases project endpoints will be able to consider the relationships in reverse. Giving
    //                   the relationships names, consider (a1)-[r1]->(b), (b)-[r2]->(a1). Then there will be two valid
    //                   undirected paths with the relationships in the given order. Namely, (a1)-[r1]->(b)-[r2]->(a1)
    //                   and (b)<-[r1]-(a1)<-[r2]-(b). Thus it will produce two rows, (a1, a1) and (b, b)
    //
    // The case with triplets (b1, a, b2) behaves analogously. In summary, we will produce the rows:
    //
    //    - For every a \in A, b \in B we produce the four rows (a, b), (a, b), (b, a), (b, a)
    //    - For every pair a1 \in A, _ \in B, a2 \in A where a1 != a2 we produce (a1, a2)
    //    - For every pair b1 \in B ,_ \in A,  b2 \in B where b1 != b2 we produce (b1, b2)
    //    - For every pair a \in A, b \in B we produce the two rows (a, a) and (b, b)
    //    - For every pair b \in B, a \in A we produce the two rows (b, b) and (a, a)

    val oneHops = (
      for {
        a <- aNodes
        b <- bNodes
      } yield {
        Array(Array(a, b), Array(b, a), Array(b, a), Array(a, b))
      }
    ).flatten

    val twoHopsABANoLoop =
      for {
        a1 <- aNodes
        _ <- bNodes
        a2 <- aNodes
        if a1 != a2
      } yield {
        Array(a1, a2)
      }

    val twoHopsBABNoLoop =
      for {
        b1 <- bNodes
        _ <- aNodes
        b2 <- bNodes
        if b1 != b2
      } yield {
        Array(b1, b2)
      }

    val twoHopsABAWithLoop = {
      for {
        a <- aNodes
        b <- bNodes
      } yield {
        Array(Array(a, a), Array(b, b))
      }
    }.flatten

    val twoHopsBABWithLoop = {
      for {
        b <- bNodes
        a <- aNodes
      } yield {
        Array(Array(b, b), Array(a, a))
      }
    }.flatten

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = oneHops ++ twoHopsABANoLoop ++ twoHopsBABNoLoop ++ twoHopsABAWithLoop ++ twoHopsBABWithLoop

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength undirected with type, nothing in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA*]-(y)", startInScope = false, endInScope = false)
      .expandAll("(n)-[r*1..2]->()")
      .allNodeScan("n")
      .build()

    // The one hops are like above, except we filter out one hops of type RB. Thus we get half.
    // There are no two hops due to the relationship type filter.

    val oneHops =
      (for {
        a <- aNodes
        b <- bNodes
      } yield {
        Array(Array(a, b), Array(b, a))
      }).flatten

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = oneHops

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength undirected, end in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:*]-(y)", startInScope = false, endInScope = true)
      .expand("(y)<-[r*1..2]-()", projectedDir = SemanticDirection.INCOMING)
      .allNodeScan("y")
      .build()

    // In the var expand, we have bound y to the first relationship in r.
    // In the project endpoints, we have bound y to the last relationship in r.
    // Moreover, the relationship list is directed in the expand.
    // The only relationship lists that satisfy the above requirements are
    // * of length 1, or
    // * of length 2, if the start and end are identical.

    val oneHops = (
      for {
        a <- aNodes
        b <- bNodes
      } yield {
        Array(Array(a, b), Array(b, a))
      }
    ).flatten

    val twoHops =
      for {
        node <- aNodes ++ bNodes
        _ <- 0 until nNodes
      } yield {
        Array(node, node)
      }

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = oneHops ++ twoHops

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength undirected with type, end in scope") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r:RA*]-(y)", startInScope = false, endInScope = true)
      .expand("(y)<-[r*1..2]-()", projectedDir = SemanticDirection.INCOMING)
      .allNodeScan("y")
      .build()

    // In the var expand, we have bound y to the first relationship in r.
    // In the project endpoints, we have bound y to the last relationship in r.
    // Moreover, the relationship list is directed in the expand, and confined to a relationship type in the projection.
    // The only relationship lists that satisfy the above requirements are of length 1.
    // Also, now we only care about RA relationships that are coming into y, which fixes y \in B

    val oneHops = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(a, b)
    }

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = oneHops

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  // ====================================================================
  // =========================INVALID CASES==============================
  // ====================================================================

  test("start is in scope but does not match relationship source") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (_, bNodes, aRels, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("b", "a")
      .projectEndpoints("(b)-[r]->(a)", startInScope = true, endInScope = false)
      .input(nodes = Seq("b"), relationships = Seq("r"), nullable = false)
      .build()

    // Zip bNodes with aRels: The aRels can never have a `b` as the source node.
    val input = batchedInputValues(sizeHint / 8, bNodes.zip(aRels).map { case (b, r) => Array[Any](b, r) }: _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    runtimeResult should beColumns("b", "a").withNoRows()
  }

  test("end is in scope but does not match relationship target") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, _, aRels, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("b", "a")
      .projectEndpoints("(b)-[r]->(a)", startInScope = false, endInScope = true)
      .input(nodes = Seq("a"), relationships = Seq("r"), nullable = false)
      .build()

    // Zip aNodes with aRels: The aRels can never have an `a` as the target node.
    val input = batchedInputValues(sizeHint / 8, aNodes.zip(aRels).map { case (a, r) => Array[Any](a, r) }: _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    runtimeResult should beColumns("b", "a").withNoRows()
  }

  test("start is in scope but does not match relationship list") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, _, _, _) = given { bidirectionalBipartiteGraph(nNodes, "A", "B", "R", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a", "b")
      .projectEndpoints("(a)-[rs*]->(b)", startInScope = true, endInScope = false)
      .input(nodes = Seq("a"), variables = Seq("rs"), nullable = false)
      .build()

    val aNode_1 = aNodes.head

    // ~ sizeHint n.o paths of length 4 not starting from aNode_1
    val relLists = for {
      n1 <- aNodes.tail
      r1 <- n1.getRelationships(OUTGOING).asScala.take(Math.pow(sizeHint, 1.0 / 4).toInt)
      n2 = r1.getOtherNode(n1)
      r2 <- n2.getRelationships(OUTGOING).asScala.take(Math.pow(sizeHint, 1.0 / 4).toInt)
      n3 = r2.getOtherNode(n2)
      r3 <- n3.getRelationships(OUTGOING).asScala.take(Math.pow(sizeHint, 1.0 / 4).toInt)
      n4 = r3.getOtherNode(n3)
      r4 <- n4.getRelationships(OUTGOING).asScala.take(Math.pow(sizeHint, 1.0 / 4).toInt)
    } yield {
      Seq(r1, r2, r3, r4).asJava
    }

    val input = batchedInputValues(sizeHint / 8, relLists.map(relList => Array[Any](aNode_1, relList)): _*).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    runtimeResult should beColumns("a", "b").withNoRows()
  }

  test("relationship list does not produce any valid paths - BOTH directions but nodes don't match") {
    // given
    val nNodes = sizeHint
    val (_, rels) = given { circleGraph(nNodes) }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a", "b")
      .projectEndpoints("(a)-[rs*]-(b)", startInScope = true, endInScope = false)
      .input(nodes = Seq("a"), variables = Seq("rs"), nullable = false)
      .build()

    val invalidPaths = for (i <- 1 until nNodes - 1) yield {
      rels.take(i) ++ rels.drop(i + 1)
    }

    val input = batchedInputValues(
      sizeHint / 8,
      invalidPaths.map(invalidPath => Array[Any](invalidPath.head.getStartNode, invalidPath.asJava)): _*
    ).stream()
    val runtimeResult = execute(logicalQuery, runtime, input)

    // then
    runtimeResult should beColumns("a", "b").withNoRows()
  }

  test("relationship list does not produce any valid paths - nodes match but not in INCOMING direction") {
    // given
    val nNodes = sizeHint
    given { circleGraph(nNodes) }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a2", "b")
      .projectEndpoints("(a2)<-[r*]-(b)", startInScope = false, endInScope = false)
      .expandAll("(a)-[r*2..4]->()")
      .allNodeScan("a")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("a2", "b").withNoRows()
  }

  test("relationship list does not produce any valid paths - nodes match but not in OUTGOING direction") {
    // given
    val nNodes = sizeHint
    given { circleGraph(nNodes) }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a2", "b")
      .projectEndpoints("(a2)-[r*]->(b)", startInScope = false, endInScope = false)
      .expand("(a)<-[r*2..4]-()", projectedDir = SemanticDirection.INCOMING)
      .allNodeScan("a")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("a2", "b").withNoRows()
  }

  test("empty relationship list, directed, nothing in scope is not supported") {
    // given
    given { nodeGraph(sizeHint) }

    // when
    val builder = new LogicalQueryBuilder(this)
      .produceResults("a2", "b2")
      .projectEndpoints("(a2)-[r*0..]->(b2)", startInScope = false, endInScope = false)
      .expand("(a)-[r*0..]->(b)")
      .allNodeScan("a")

    // then
    an[IllegalArgumentException] should be thrownBy {
      builder.build()
    }
  }

  test("empty relationship list, undirected, nothing in scope is not supported") {
    // given
    given { nodeGraph(sizeHint) }

    // when
    val builder = new LogicalQueryBuilder(this)
      .produceResults("a2", "b2")
      .projectEndpoints("(a2)-[r*0..]-(b2)", startInScope = false, endInScope = false)
      .expand("(a)-[r*0..]->(b)")
      .allNodeScan("a")

    // then
    an[IllegalArgumentException] should be thrownBy {
      builder.build()
    }
  }

  test("empty relationship list, directed, start in scope, return 1 row per incoming row") {
    // given
    val nodes = given { nodeGraph(sizeHint) }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a", "b2")
      .projectEndpoints("(a)-[r*0..]->(b2)", startInScope = true, endInScope = false)
      .expand("(a)-[r*0..]->(b)")
      .allNodeScan("a")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    val expected = nodes.map(node => Array(node, node))

    // then
    runtimeResult should beColumns("a", "b2").withRows(expected)
  }

  test("empty relationship list, directed, both in scope, return 1 row per incoming row") {
    // given
    val nodes = given { nodeGraph(sizeHint) }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a", "b")
      .projectEndpoints("(a)-[r*0..]->(b)", startInScope = true, endInScope = true)
      .expand("(a)-[r*0..]->(b)")
      .allNodeScan("a")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    val expected = nodes.map(node => Array(node, node))

    // then
    runtimeResult should beColumns("a", "b").withRows(expected)
  }

  test("empty relationship list, undirected, start in scope, return 1 row per incoming row") {
    // given
    val nodes = given { nodeGraph(sizeHint) }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a", "b2")
      .projectEndpoints("(a)-[r*0..]-(b2)", startInScope = true, endInScope = false)
      .expand("(a)-[r*0..]->(b)")
      .allNodeScan("a")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    val expected = nodes.map(node => Array(node, node))

    // then
    runtimeResult should beColumns("a", "b2").withRows(expected)
  }

  // TODO tests that we don't return a result if a relationship does not exist in the graph: single and var-length

  test("1-hop loops nothing in scope") {
    // These 1-hop undirected cases are straightforwards but we initially misunderstood them as complicated.
    // We are keeping the tests for this reason.

    // given
    val (nodes, rs, loops) = given {
      val start = runtimeTestSupport.tx.createNode(Label.label("Start"))
      val n1 = runtimeTestSupport.tx.createNode()
      val n2 = runtimeTestSupport.tx.createNode()
      val n3 = runtimeTestSupport.tx.createNode()
      val end = runtimeTestSupport.tx.createNode(Label.label("End"))

      val r0 = start.createRelationshipTo(n1, RelationshipType.withName("R"))
      val r1 = n1.createRelationshipTo(n2, RelationshipType.withName("R"))
      val r2 = n2.createRelationshipTo(n3, RelationshipType.withName("R"))
      val r3 = n3.createRelationshipTo(end, RelationshipType.withName("R"))

      val loop0 = n1.createRelationshipTo(n1, RelationshipType.withName("LOOP"))
      val loop1 = n1.createRelationshipTo(n1, RelationshipType.withName("LOOP"))
      val loop2 = n2.createRelationshipTo(n2, RelationshipType.withName("LOOP"))

      (Seq(start, n1, n2, n3, end), Seq(r0, r1, r2, r3), Seq(loop0, loop1, loop2))
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a", "rs", "b")
      .projectEndpoints("(a)-[rs*]-(b)", startInScope = false, endInScope = false)
      .expandInto(s"(start)-[rs*7..7]->(end)")
      .cartesianProduct()
      .|.nodeByLabelScan("end", "End")
      .nodeByLabelScan("start", "Start")
      .build()

    val expected = Seq(
      Array[Object](
        nodes.head,
        Seq(rs(0), loops(0), loops(1), rs(1), loops(2), rs(2), rs(3)).asJava,
        nodes.last
      ),
      Array[Object](
        nodes.head,
        Seq(rs(0), loops(1), loops(0), rs(1), loops(2), rs(2), rs(3)).asJava,
        nodes.last
      )
    )

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("a", "rs", "b").withRows(expected)
  }

  test("1-hop loops end in scope") {
    // These 1-hop undirected cases are straightforwards but we initially misunderstood them as complicated.
    // We are keeping the tests for this reason.

    // given
    val (nodes, rs, loops) = given {
      val start = runtimeTestSupport.tx.createNode(Label.label("Start"))
      val n1 = runtimeTestSupport.tx.createNode()
      val n2 = runtimeTestSupport.tx.createNode()
      val n3 = runtimeTestSupport.tx.createNode()
      val end = runtimeTestSupport.tx.createNode(Label.label("End"))

      val r0 = start.createRelationshipTo(n1, RelationshipType.withName("R"))
      val r1 = n1.createRelationshipTo(n2, RelationshipType.withName("R"))
      val r2 = n2.createRelationshipTo(n3, RelationshipType.withName("R"))
      val r3 = n3.createRelationshipTo(end, RelationshipType.withName("R"))

      val loop0 = n1.createRelationshipTo(n1, RelationshipType.withName("LOOP"))
      val loop1 = n1.createRelationshipTo(n1, RelationshipType.withName("LOOP"))
      val loop2 = n2.createRelationshipTo(n2, RelationshipType.withName("LOOP"))

      (Seq(start, n1, n2, n3, end), Seq(r0, r1, r2, r3), Seq(loop0, loop1, loop2))
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a", "rs", "end")
      .projectEndpoints("(a)-[rs*]-(end)", startInScope = false, endInScope = true)
      .expandInto(s"(start)-[rs*7..7]->(end)")
      .cartesianProduct()
      .|.nodeByLabelScan("end", "End")
      .nodeByLabelScan("start", "Start")
      .build()

    val expected = Seq(
      Array[Object](
        nodes.head,
        Seq(rs(0), loops(0), loops(1), rs(1), loops(2), rs(2), rs(3)).asJava,
        nodes.last
      ),
      Array[Object](
        nodes.head,
        Seq(rs(0), loops(1), loops(0), rs(1), loops(2), rs(2), rs(3)).asJava,
        nodes.last
      )
    )

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("a", "rs", "end").withRows(expected)
  }

  test("should project endpoints - start in scope and is reference") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes: Seq[Node], bNodes: Seq[Node]) = given { bipartiteGraph(nNodes, "A", "B", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(x)-[r]->(y)", startInScope = true, endInScope = false)
      .expandAll("(x)-[r]->()")
      .input(variables = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, inputValues((aNodes ++ bNodes).map(n => Array[Any](n)): _*))

    // then
    val expected = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(a, b)
    }

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - end node in scope and is reference") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes: Seq[Node], bNodes: Seq[Node]) = given { bipartiteGraph(nNodes, "A", "B", "R") }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "y")
      .projectEndpoints("(y)<-[r]-(x)", startInScope = false, endInScope = true)
      .expandAll("(x)-[r]->()")
      .input(variables = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, inputValues((aNodes ++ bNodes).map(n => Array[Any](n)): _*))

    // then
    val expected = for {
      a <- aNodes
      b <- bNodes
    } yield {
      Array(a, b)
    }

    runtimeResult should beColumns("x", "y").withRows(expected)
  }

  test("should project endpoints - varlength - start node in scope and is reference") {
    // given
    val nNodes = Math.sqrt(sizeHint).ceil.toInt
    val (aNodes, bNodes, _, _) = given {
      bidirectionalBipartiteGraph(nNodes, "A", "B", "RA", "RB")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .projectEndpoints("(x)-[r*]-(y)", startInScope = true, endInScope = false)
      .expand("(x)<-[r*1..2]-()", projectedDir = SemanticDirection.INCOMING)
      .input(variables = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, inputValues((aNodes ++ bNodes).map(n => Array[Any](n)): _*))

    // then
    val expected = (for {
      node <- aNodes ++ bNodes
      oneStep =
        node.getRelationships(INCOMING).asScala.map(r =>
          Array[Any](node, Collections.singletonList(r), r.getOtherNode(node))
        )
      twoSteps = oneStep.flatMap {
        case Array(_, rs, b) =>
          val r = rs.asInstanceOf[java.util.List[Relationship]].get(0)
          b.asInstanceOf[Node].getRelationships(INCOMING).asScala.map(r2 =>
            Array[Any](node, java.util.List.of(r, r2), r2.getOtherNode(b.asInstanceOf[Node]))
          )
      }
    } yield oneStep ++ twoSteps).flatten

    runtimeResult should beColumns("x", "r", "y").withRows(expected)
  }
}
