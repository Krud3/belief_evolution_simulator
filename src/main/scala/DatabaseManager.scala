import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import java.io.ByteArrayInputStream
import java.sql.{Connection, PreparedStatement, Statement}
import java.util.UUID
import scala.collection.IndexedSeqView
import scala.collection.mutable.ArrayBuffer


object DatabaseManager {
    private val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl("jdbc:postgresql://localhost:5433/promueva_new")
    hikariConfig.setUsername("postgres")
    hikariConfig.setPassword("postgres")
    
    // Parallel
    hikariConfig.setMaximumPoolSize(32) // Slightly higher than CPU cores
    hikariConfig.setMinimumIdle(16)     // Half of max pool size
    
    // Set the maximum lifetime of a connection in the pool.
    hikariConfig.setMaxLifetime(3_600_000) // 1 hour
    
    // Set the connection timeout: how long the client will wait for a connection from the pool.
    hikariConfig.setConnectionTimeout(60_000) // 1 min
    
    // Set the idle timeout: how long a connection can remain idle before being closed.
    hikariConfig.setIdleTimeout(900_000) // 15 minutes
    
    // Additional properties for prepared statement caching.
    hikariConfig.addDataSourceProperty("cachePrepStmts", "true")
    hikariConfig.addDataSourceProperty("prepStmtCacheSize", "500")
    hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "4096")
    
    hikariConfig.addDataSourceProperty("useServerPrepStmts", "true")
    hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true")
    
    private val dataSource = new HikariDataSource(hikariConfig)
    
    private def getConnection: Connection = dataSource.getConnection
    
    // Inserts
    private def setPreparedStatementInt(stmt: PreparedStatement, parameterIndex: Int, value: Option[Int]): Unit = {
        value match {
            case Some(f) => stmt.setInt(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.INTEGER)
        }
    }
    
    private def setPreparedStatementFloat(stmt: PreparedStatement, parameterIndex: Int, value: Option[Float]): Unit = {
        value match {
            case Some(f) => stmt.setFloat(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.FLOAT)
        }
    }
    
    private def setPreparedStatementLong(stmt: PreparedStatement, parameterIndex: Int, value: Option[Long]): Unit = {
        value match {
            case Some(f) => stmt.setFloat(parameterIndex, f)
            case None => stmt.setNull(parameterIndex, java.sql.Types.BIGINT)
        }
    }
    
    private def setPreparedStatementString(stmt: PreparedStatement, parameterIndex: Int, value: Option[String]): Unit = {
        value match {
            case Some(str) => stmt.setString(parameterIndex, str)
            case None => stmt.setNull(parameterIndex, java.sql.Types.VARCHAR)
        }
    }
    
    def createRun(
                   runMode: RunMode,
                   saveMode: SaveMode,
                   numberOfNetworks: Int,
                   density: Option[Int],
                   degreeDistribution: Option[Float],
                   stopThreshold: Float,
                   iterationLimit: Int,
                   initialDistribution: String
                 ): Option[Int] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        
        try {
            conn.setAutoCommit(false) 
            
            val sql = density match {
                case Some(_) => """
                WITH inserted_run AS (
                    INSERT INTO runs (
                        number_of_networks, iteration_limit, stop_threshold,
                        initial_distribution, run_mode, save_mode
                    ) 
                    VALUES (?, ?, ?, CAST(? AS initial_distribution), ?, ?)
                    RETURNING id
                )
                INSERT INTO generated_run_parameters (run_id, degree_distribution, density)
                SELECT id, ?, ?
                FROM inserted_run
                RETURNING run_id;
                """
                case None => """
                INSERT INTO runs (
                    number_of_networks, iteration_limit, stop_threshold,
                    initial_distribution, run_mode, save_mode
                ) 
                VALUES (?, ?, ?, CAST(? AS initial_distribution), ?, ?)
                RETURNING id;
                """
            }
            
            stmt = conn.prepareStatement(sql)
            
            // Common parameters
            stmt.setInt(1, numberOfNetworks)
            stmt.setInt(2, iterationLimit)
            stmt.setFloat(3, stopThreshold)
            stmt.setString(4, initialDistribution)
            stmt.setShort(5, 1.toShort)
            stmt.setShort(6, 1.toShort)
            
            // Additional parameters for generated runs
            density.foreach { d =>
                stmt.setFloat(7, degreeDistribution.getOrElse(
                    throw new IllegalArgumentException("Degree distribution required for generated runs")))
                stmt.setInt(8, d)
            }
            
            val rs = stmt.executeQuery()
            val result = if (rs.next()) Some(rs.getInt(1)) else None
            
            conn.commit()
            result
        } catch {
            case e: Exception =>
                conn.rollback()
                throw e
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) {
                conn.setAutoCommit(true)
                conn.close()
            }
        }
    }
    
    /*
    ToDo make function insert networks as a batch
     */
    def createNetwork
    (
      id: UUID,
      name: String,
      runId: Int,
      numberOfAgents: Int
    ): Unit = {
        val conn = getConnection
        try {
            conn.setAutoCommit(true)
            val sql =
                """
                INSERT INTO networks (
                    id, run_id, number_of_agents, name
                ) VALUES (CAST(? AS uuid), ?, ?, ?) RETURNING id;
                """
            val stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
            stmt.setObject(1, id)
            stmt.setInt(2, runId)
            stmt.setInt(3, numberOfAgents)
            stmt.setString(4, name)
            
            stmt.executeUpdate()
            stmt.close()
        } finally {
            conn.close()
        }
    }
    
    def insertAgentsBatch(agents: StaticData): Unit = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            conn.setAutoCommit(true)
            val sql = """
            INSERT INTO public.agents (
                id, network_id, number_of_neighbors, tolerance_radius, tol_offset,
                silence_strategy, silence_effect, belief_update_method,
                expression_threshold, open_mindedness, name
            ) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS silence_strategy),
            CAST(? AS silence_effect), CAST(? AS belief_update_method), ?, ?, ?);
        """
            stmt = conn.prepareStatement(sql)
            
            var i = 0
            val size = agents.static.size
            while (i < size) {
                val agent = agents.static(i)
                stmt.setObject(1, agent.id)
                stmt.setObject(2, agents.networkId)
                stmt.setInt(3, agent.numberOfNeighbors)
                stmt.setFloat(4, agent.toleranceRadius)
                stmt.setFloat(5, agent.tolOffset)
                stmt.setString(6, agent.causeOfSilence)
                stmt.setString(7, agent.effectOfSilence)
                stmt.setString(8, agent.beliefUpdateMethod)
                setPreparedStatementFloat(stmt, 9, agent.beliefExpressionThreshold)
                setPreparedStatementInt(stmt, 10, agent.openMindedness)
                setPreparedStatementString(stmt, 11, agent.name)
                stmt.addBatch()
                i += 1
            }
            stmt.executeBatch()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    // ToDo optimize insert to maybe use copy or batched concurrency
    def insertNeighborsBatch(networkStructures: IndexedSeqView[NeighborStructure]): Unit = {
        val conn = dataSource.getConnection
        try {
            val sql =
                """
            INSERT INTO public.neighbors (
                source, target, value, cognitive_bias
            ) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS cognitive_bias));
            """
            val stmt = conn.prepareStatement(sql)
            var i = 0
            val size = networkStructures.size
            while (i < size) {
                val networkStructure = networkStructures(i)
                stmt.setObject(1, networkStructure.source)
                stmt.setObject(2, networkStructure.target)
                stmt.setFloat(3, networkStructure.value)
                stmt.setString(4, networkStructure.bias.toString)
                stmt.addBatch()
                i += 1
            }
            
            stmt.executeBatch()
            stmt.close()
        } catch {
            case e: Exception =>
                e.printStackTrace() // Properly handle exceptions
        } finally {
            if (conn != null) conn.close()
        }
    }
    
    private def createUnloggedTable(tableName: String): Unit = {
        val conn = getConnection
        var stmt: Statement = null
        try {
            stmt = conn.createStatement()
            stmt.execute(
                s"""
                CREATE UNLOGGED TABLE IF NOT EXISTS public.$tableName (
                    agent_id uuid NOT NULL,
                    round integer NOT NULL,
                    belief real NOT NULL,
                    state_data bytea
                );
                """
            )
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    def insertRounds(dataOut: Array[Byte], tableName: String): Unit = {
        val conn = getConnection
        var copyManager: CopyManager = null
        try {
            conn.setAutoCommit(false)
            createUnloggedTable(tableName)
            copyManager = new CopyManager(conn.unwrap(classOf[BaseConnection]))
            val reader = new ByteArrayInputStream(dataOut)
            copyManager.copyIn(s"COPY public.$tableName FROM STDIN WITH (FORMAT BINARY)", reader)
            conn.commit()
            reader.close()
        } catch {
            case e: Exception =>
                e.printStackTrace()
                throw e
        } finally {
            if (conn != null) conn.close()
        }
    }
    
    
    def flushRoundTable(tempTableName: String, targetTable: String): Unit = {
        val disableTriggersSql = s"ALTER TABLE public.$targetTable DISABLE TRIGGER ALL"
        val enableTriggersSql = s"ALTER TABLE public.$targetTable ENABLE TRIGGER ALL"
        val insertSql = s"""
                    INSERT INTO public.$targetTable (agent_id, round, belief, state_data)
                    SELECT agent_id, round, belief, state_data
                    FROM public.$tempTableName
                    ON CONFLICT (agent_id, round) DO NOTHING
                    """;
            
        val dropSql = s"DROP TABLE IF EXISTS public.$tempTableName"
        val conn = getConnection
        var stmt: Statement = null
        try {
            conn.setAutoCommit(false)
            stmt = conn.createStatement()
        
            // Disable triggers
            stmt.execute(disableTriggersSql)
            
            stmt.execute(insertSql)
            
            // Enable triggers
            stmt.execute(enableTriggersSql)
            
            // Execute DROP TABLE
            stmt.execute(dropSql)
            
            conn.commit()
        } catch {
            case e: org.postgresql.util.PSQLException
                if e.getMessage.contains("relation") && e.getMessage.contains("does not exist") ||
                  e.getMessage.contains("current transaction is aborted") =>
                conn.rollback()
            case e: Exception =>
                conn.rollback()
                e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    //Updates
    def updateTimeField(id: Either[Int, UUID], timeValue: Long, table: String, field: String): Unit = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql = id match {
                case Left(intId) => s"UPDATE $table SET $field = ? WHERE id = ?"
                case Right(uuid) => s"UPDATE $table SET $field = ? WHERE id = CAST(? AS uuid)"
            }
            
            stmt = conn.prepareStatement(sql)
            stmt.setLong(1, timeValue)
            
            id match {
                case Left(intId) => stmt.setInt(2, intId)
                case Right(uuid) => stmt.setString(2, uuid.toString)
            }
            
            stmt.executeUpdate()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    
    def updateNetworkFinalRound(id: UUID, finalRound: Int, simulationOutcome: Boolean): Unit = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql = s"UPDATE networks SET final_round = ?, simulation_outcome = ? WHERE id = CAST(? AS uuid)"
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, finalRound)
            stmt.setBoolean(2, simulationOutcome)
            stmt.setString(3, id.toString)
            stmt.executeUpdate()
            stmt.close()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            conn.close()
        }
    }
    
    //Queries
//    def reRunSpecificNetwork(id: UUID, agentType: AgentType): Array[AgentInitialState] = {
//        val conn = getConnection
//        var stmt: PreparedStatement = null
//        try {
//            val sql =
//                s"""
//                WITH InitialBeliefs AS (
//                    SELECT
//                        rd.agent_id,
//                        rd.belief AS initial_belief
//                    FROM
//                        public.combined_round_data rd
//                    JOIN
//                        public.agents a ON rd.agent_id = a.id
//                    WHERE
//                        a.network_id = ?::uuid
//                        AND rd.round = 0
//                ),
//                AgentTypes AS (
//                    SELECT
//                        id AS agent_id,
//                        cause_of_silence,
//                        effect_of_silence,
//                        belief_update_method,
//                        tolerance_radius,
//						tol_offset
//                    FROM
//                        public.agents
//                    WHERE
//                        network_id = ?::uuid
//                ),
//                Neighbors AS (
//                    SELECT
//                        ns.target AS agent_id,
//                        STRING_AGG(ns.source::text, ',') AS neighbor_ids,
//                        STRING_AGG(ns.value::text, ',') AS neighbor_values
//                    FROM
//                        public.networks_structure ns
//                    JOIN
//                        public.agents a ON ns.target = a.id
//                    WHERE
//                        a.network_id = ?::uuid
//                    GROUP BY
//                        ns.target
//                )
//                SELECT
//                    ib.agent_id,
//                    ib.initial_belief,
//                    at.cause_of_silence,
//                    at.effect_of_silence,
//                    at.belief_update_method,
//                    at.tolerance_radius,
//					at.tol_offset,
//                    n.neighbor_ids,
//                    n.neighbor_values
//                FROM
//                    InitialBeliefs ib
//                JOIN
//                    AgentTypes at ON ib.agent_id = at.agent_id
//                LEFT JOIN
//                    Neighbors n ON ib.agent_id = n.agent_id
//                ORDER BY
//                    ib.agent_id;
//                """
//            stmt = conn.prepareStatement(sql)
//            stmt.setString(1, id.toString)
//            stmt.setString(2, id.toString)
//            stmt.setString(3, id.toString)
//            val resultSet = stmt.executeQuery()
//            
//            val agentInitialStates = ArrayBuffer[AgentInitialState]()
//            while (resultSet.next()) {
//                val agentId = resultSet.getString("agent_id")
//                val initialBelief = resultSet.getFloat("initial_belief")
//                val neighborIds = Option(resultSet.getString("neighbor_ids")).getOrElse("").split(",").filter(_.nonEmpty)
//                val neighborValues = Option(resultSet.getString("neighbor_values")).getOrElse("").split(",").filter(_.nonEmpty).map(_.toFloat)
//                val neighbors = neighborIds.zip(neighborValues)
//                val toleranceRadius = resultSet.getFloat("tolerance_radius")
//                val toleranceOffset = resultSet.getFloat("tol_offset")
//                
//                val agentInitialState = AgentInitialState(agentId, initialBelief, agentType, neighbors, toleranceRadius)
//                
//                agentInitialStates += agentInitialState
//            }
//            
//            return agentInitialStates.toArray
//        } catch {
//            case e: Exception => e.printStackTrace()
//        } finally {
//            if (stmt != null) stmt.close()
//            if (conn != null) conn.close()
//        }
//        Array.empty[AgentInitialState]
//    }
    
}
