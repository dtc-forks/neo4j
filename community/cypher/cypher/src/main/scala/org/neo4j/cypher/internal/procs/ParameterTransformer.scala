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
package org.neo4j.cypher.internal.procs

import ParameterTransformer.ParameterConversionFunction
import ParameterTransformer.ParameterTransformerOutput
import org.neo4j.cypher.internal.procs.ParameterTransformer.ParameterGenerationFunction
import org.neo4j.cypher.internal.procs.ParameterTransformer.safeMergeParameters
import org.neo4j.cypher.internal.util.InternalNotification
import org.neo4j.exceptions.InvalidArgumentException
import org.neo4j.graphdb.Transaction
import org.neo4j.internal.kernel.api.security.SecurityContext
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.MapValue

case class ParameterTransformer(
  genFunc: ParameterGenerationFunction = (_, _) => MapValue.EMPTY,
  transformFunc: (Transaction, MapValue) => ParameterTransformerOutput = (_, params) => (params, Set.empty)
) {

  def convert(convFunc: ParameterConversionFunction): ParameterTransformer = {
    ParameterTransformer(genFunc, (tx, mv) => (convFunc(tx, transformFunc(tx, mv)._1), Set.empty))
  }

  def generate(generator: ParameterGenerationFunction): ParameterTransformer = {
    val newGenFunc: ParameterGenerationFunction = (tx, sc) => genFunc(tx, sc).updatedWith(generator(tx, sc))
    ParameterTransformer(newGenFunc, transformFunc)
  }

  def validate(validFunc: (Transaction, MapValue) => ParameterTransformerOutput): ParameterTransformer = {
    ParameterTransformer(
      genFunc,
      (tx, mv) => {
        val (input, notifications) = transformFunc(tx, mv)
        val (output, newNotifications) = validFunc(tx, input)
        (output, notifications ++ newNotifications)
      }
    )
  }

  def transform(
    tx: Transaction,
    sc: SecurityContext,
    systemParams: MapValue,
    userParams: MapValue
  ): ParameterTransformerOutput = {
    transformFunc.apply(tx, safeMergeParameters(systemParams, userParams, genFunc(tx, sc)))
  }
}

object ParameterTransformer {
  type ParameterTransformerOutput = (MapValue, Set[InternalNotification])
  type ParameterConversionFunction = (Transaction, MapValue) => MapValue
  type ParameterGenerationFunction = (Transaction, SecurityContext) => MapValue

  def apply(genFunc: (Transaction, SecurityContext) => MapValue): ParameterTransformer =
    ParameterTransformer(genFunc, (_, params) => (params, Set.empty))

  private def safeMergeParameters(systemParams: MapValue, userParams: MapValue, initialParams: MapValue): MapValue = {
    val updatedSystemParams: MapValue = systemParams.updatedWith(initialParams)
    updatedSystemParams.foreach {
      case (_, Values.NO_VALUE) => // placeholders should be replaced
      case (key, _) => if (userParams.containsKey(key))
          throw new InvalidArgumentException(s"The query contains a parameter with an illegal name: '$key'")
    }
    updatedSystemParams.updatedWith(userParams)
  }
}
