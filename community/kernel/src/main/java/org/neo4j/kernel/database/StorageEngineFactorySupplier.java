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
package org.neo4j.kernel.database;

import org.neo4j.storageengine.api.StorageEngineFactory;

/**
 * This is a supplier of {@link StorageEngineFactory}.
 * It's used to supply a {@link Database} of the necessary factory (and in turn the engine) at the time when the database is initiated.
 * When the database object is created the correct engine to use is not guaranteed to be known.
 */
public interface StorageEngineFactorySupplier {
    StorageEngineFactory create();
}
