import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import odbf.Decoder
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import java.io.{ByteArrayInputStream, PrintWriter}
import java.sql.{Connection, PreparedStatement, Statement}
import java.util.UUID
import scala.collection.{IndexedSeqView, mutable}
import scala.collection.mutable.ArrayBuffer


object DatabaseManager {
    private val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl("jdbc:postgresql://localhost:5433/promueva_new")
    hikariConfig.setUsername("postgres")
    hikariConfig.setPassword("postgres")
    
    // Parallel
    hikariConfig.setMaximumPoolSize(32)
    hikariConfig.setMinimumIdle(16)
    
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
            val size = agents.static.length
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
    def insertNeighborsBatch(networkStructures: ArrayBuffer[NeighborStructure]): Unit = {
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
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
    }
    
    // Queries
    case class RunQueryResult(numberOfNetworks: Int, iterationLimit: Int, stopThreshold: Float, 
        distribution: String, density: Option[Int], degreeDistribution: Option[Float])
    
    def getRun(runId: Int): Option[RunQueryResult] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql = """
                  |SELECT * FROM runs LEFT JOIN
                  |generated_run_parameters on runs.id = generated_run_parameters.run_id
                  |WHERE id = ?;
                  |""".stripMargin
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            val queryResult = stmt.executeQuery()
            if (queryResult.next()) {
                return Some(RunQueryResult(
                    queryResult.getInt("number_of_networks"),
                    queryResult.getInt("iteration_limit"),
                    queryResult.getFloat("stop_threshold"),
                    queryResult.getString("initial_distribution"),
                    Option(queryResult.getObject("density")).map(_.toString.toInt),
                    Option(queryResult.getObject("degree_distribution")).map(_.toString.toFloat)
                    ))
            }
        } catch {
            case e: Exception =>
                e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getRunInfo(networkId: UUID): Option[(String, Option[Int], Option[Float])] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT initial_distribution, density, degree_distribution
                  |    FROM runs
                  |LEFT JOIN
                  |    generated_run_parameters on runs.id = generated_run_parameters.run_id
                  |WHERE id = (SELECT run_id FROM networks WHERE id = CAST(? AS uuid));
                  |""".stripMargin
            stmt.setObject(1, networkId)
            stmt = conn.prepareStatement(sql)
            val queryResult = stmt.executeQuery()
            if (queryResult.next()) {
                return Some(
                    (queryResult.getString("initial_distribution"),
                      Option(queryResult.getObject("density")).map(_.toString.toInt),
                      Option(queryResult.getObject("degree_distribution")).map(_.toString.toInt)))
            }
            
        } catch {
            case e: Exception =>
                e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getNetworks(runId: Int, numberOfNetworks: Int): Option[Array[UUID]] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql = s"SELECT id FROM networks WHERE run_id = ?"
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            val queryResult = stmt.executeQuery()
            val networkIdArray = new Array[UUID](numberOfNetworks)
            var i = 0
            while (queryResult.next()) {
                networkIdArray(i) = queryResult.getObject(1, classOf[UUID])
                i += 1
            }
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getAgents(networkId: UUID, numberOfAgents: Int): Option[
      Array[(UUID, Float, Float, Option[Float], Option[Integer])]] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT id, tolerance_radius, tol_offset, expression_threshold, open_mindedness
                  |FROM agents
                  |WHERE network_id = = CAST(? AS uuid)
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setObject(1, networkId)
            val queryResult = stmt.executeQuery()
            val resultArray = new Array[(UUID, Float, Float, Option[Float], Option[Integer])](numberOfAgents)
            var i = 0
            while (queryResult.next()) {
                resultArray(i) = (
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getFloat(2),
                  queryResult.getFloat(3),
                  Option(queryResult.getObject(4)).map(_.toString.toFloat),
                  Option(queryResult.getObject(5)).map(_.toString.toInt)
                )
                i += 1
            }
            if (i != 0) return Some(resultArray) 
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getAgentsWithState(networkId: UUID, numberOfAgents: Int): Option[
      Array[(UUID, Float, Float, Option[Float], Option[Integer],
        Float, Option[Array[Byte]])]] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT a.id,
                  |    tolerance_radius, 
                  |    tol_offset, 
                  |    expression_threshold, 
                  |    open_mindedness,
                  |	   belief,
                  |	   state_data
                  |FROM agents a
                  |JOIN agent_states_speaking s ON a.id = s.agent_id
                  |WHERE a.network_id = CAST(? AS uuid) AND round = 0; 
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setObject(1, networkId)
            val queryResult = stmt.executeQuery()
            val resultArray = new Array[(UUID, Float, Float, Option[Float], Option[Integer], 
              Float, Option[Array[Byte]])](numberOfAgents)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray(i) = (
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getFloat(2),
                  queryResult.getFloat(3),
                  Option(queryResult.getObject(4)).map(_.toString.toFloat),
                  Option(queryResult.getObject(5)).map(_.toString.toInt),
                  queryResult.getFloat(6),
                  Option(queryResult.getBytes(7))
                )
                i += 1
            }
            if (i != 0) return Some(resultArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getNeighbors(networkId: UUID, numberOfAgents: Int): Option[Array[(UUID, UUID, Float, 
      Option[CognitiveBiasType])]] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |SELECT n.*
                  |FROM agents a
                  |JOIN neighbors n ON a.id = n.source
                  |WHERE a.network_id = CAST(? AS uuid);
                  |
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setObject(1, networkId)
            val queryResult = stmt.executeQuery()
            val resultArray = new ArrayBuffer[(UUID, UUID, Float, Option[CognitiveBiasType])](numberOfAgents)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray(i) = (
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getObject(2, classOf[UUID]),
                  queryResult.getFloat(3),
                  CognitiveBiases.fromString(queryResult.getString(4))
                )
                i += 1
            }
            if (i != 0) return Some(resultArray.toArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getAgentsWithState(runId: Int, numberOfAgents: Int, limit: Int, offset: Int): Option[
      Array[AgentStateLoad]] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |WITH limited_networks AS (
                  |    SELECT n.id
                  |    FROM networks n
                  |    WHERE run_id = ?
                  |    LIMIT ?
                  |    OFFSET ?
                  |)
                  |SELECT (n.id) as network_id,
                  |    a.id, 
                  |    belief,
                  |    tolerance_radius, 
                  |    tol_offset, 
                  |    state_data,
                  |    expression_threshold, 
                  |    open_mindedness
                  |FROM agents a
                  |JOIN agent_states_speaking s ON a.id = s.agent_id AND round = 0
                  |JOIN networks n ON n.id = a.network_id
                  |WHERE n.id IN (SELECT id FROM limited_networks)
                  |ORDER BY n.id;
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            val queryResult = stmt.executeQuery()
            val resultArray = new Array[AgentStateLoad](numberOfAgents * limit)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray(i) = AgentStateLoad(
                  queryResult.getObject(1, classOf[UUID]),
                  queryResult.getObject(2, classOf[UUID]),
                  queryResult.getFloat(3),
                  queryResult.getFloat(4),
                  queryResult.getFloat(5),
                  Option(queryResult.getBytes(6)),
                  Option(queryResult.getObject(7)).map(_.toString.toFloat),
                  Option(queryResult.getObject(8)).map(_.toString.toInt)
                )
                i += 1
            }
            if (i != 0) return Some(resultArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def getNeighbors(runId: Int, numberOfAgents: Int, limit: Int, offset: Int): Option[Array[NeighborsLoad]] = {
        val conn = getConnection
        var stmt: PreparedStatement = null
        try {
            val sql =
                """
                  |WITH limited_networks AS (
                  |    SELECT net.id
                  |    FROM networks net
                  |    WHERE run_id = ?
                  |    LIMIT ?
                  |	   OFFSET ?
                  |)
                  |SELECT net.id as network_id, n.*
                  |FROM agents a
                  |JOIN neighbors n ON a.id = n.source
                  |JOIN networks net ON net.id = a.network_id
                  |JOIN limited_networks ln ON ln.id = net.id
                  |ORDER BY net.id, a.id;
                  |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            stmt.setInt(1, runId)
            stmt.setInt(2, limit)
            stmt.setInt(3, offset)
            val queryResult = stmt.executeQuery()
            val resultArray = new ArrayBuffer[NeighborsLoad](numberOfAgents * 2)
            var i = 0
            while (queryResult.next()) {
                val bytes = queryResult.getBytes(7)
                resultArray.addOne(NeighborsLoad(
                    queryResult.getObject(1, classOf[UUID]),
                    queryResult.getObject(2, classOf[UUID]),
                    queryResult.getObject(3, classOf[UUID]),
                    queryResult.getFloat(4),
                    CognitiveBiases.fromString(queryResult.getString(5)).get
                ))
                i += 1
            }
            if (i != 0) return Some(resultArray.toArray)
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
        }
        None
    }
    
    def exportToMaudeTXT(name: String, numberOfAgents: Int): Unit = {
        case class AgentRoundQuery(
            agentId: UUID,
            name: String,
            numberOfNeighbors: Int,
            toleranceRadius: Float,
            tolOffset: Float,
            //        silenceStrategy: String,
            //        silenceEffect: String,
            //        beliefUpdateMethod: String,
            //        expressionThreshold: Option[Float],
            //        openMindedness: Option[Int],
            round: Int,
            belief: Float,
            stateData: Array[Byte],
            isSpeaking: Boolean
        )
        val conn = getConnection
        var stmt: PreparedStatement = null

        val ids = mutable.HashMap[String, Int]()
        val writer = new PrintWriter(name)
        try {
            val sql = """
                        |WITH RECURSIVE latest_run AS (
                        |    SELECT id 
                        |    FROM runs 
                        |    ORDER BY id DESC 
                        |    LIMIT 1
                        |),
                        |latest_network AS (
                        |    SELECT id as network_id
                        |    FROM networks 
                        |    WHERE run_id = (SELECT id FROM latest_run)
                        |    ORDER BY final_round DESC
                        |    LIMIT 1
                        |),
                        |agent_states AS (
                        |    SELECT 
                        |        agent_id,
                        |        round,
                        |        belief,
                        |        state_data,
                        |        true as speaking
                        |    FROM agent_states_speaking
                        |    WHERE agent_id IN (
                        |        SELECT id 
                        |        FROM agents 
                        |        WHERE network_id = (SELECT network_id FROM latest_network)
                        |    )
                        |    UNION ALL
                        |    SELECT 
                        |        agent_id,
                        |        round,
                        |        belief,
                        |        state_data,
                        |        false as speaking
                        |    FROM agent_states_silent
                        |    WHERE agent_id IN (
                        |        SELECT id 
                        |        FROM agents 
                        |        WHERE network_id = (SELECT network_id FROM latest_network)
                        |    )
                        |)
                        |SELECT 
                        |    a.id as agent_id,
                        |    a.name,
                        |    a.number_of_neighbors,
                        |    a.tolerance_radius,
                        |    a.tol_offset,
                        |    a.silence_strategy,
                        |    a.silence_effect,
                        |    a.belief_update_method,
                        |    a.expression_threshold,
                        |    a.open_mindedness,
                        |    s.round,
                        |    s.belief,
                        |    s.state_data,
                        |    s.speaking
                        |FROM latest_network ln
                        |JOIN agents a ON a.network_id = ln.network_id
                        |JOIN agent_states s ON s.agent_id = a.id
                        |ORDER BY s.round, a.id;
                        |""".stripMargin
            
            stmt = conn.prepareStatement(sql)
            val rs = stmt.executeQuery()
            
            var printResults = true
            var i = 0
            writer.print("< ")
            while (rs.next()) {
                val result = AgentRoundQuery(
                    agentId = UUID.fromString(rs.getString("agent_id")),
                    name = Option(rs.getString("name")).getOrElse(""),
                    numberOfNeighbors = rs.getInt("number_of_neighbors"),
                    toleranceRadius = rs.getFloat("tolerance_radius"),
                    tolOffset = rs.getFloat("tol_offset"),
//                    silenceStrategy = rs.getString("silence_strategy"),
//                    silenceEffect = rs.getString("silence_effect"),
//                    beliefUpdateMethod = rs.getString("belief_update_method"),
//                    expressionThreshold = Option(rs.getFloat("expression_threshold")).filterNot(_ => rs.wasNull()),
//                    openMindedness = Option(rs.getInt("open_mindedness")).filterNot(_ => rs.wasNull()),
                    round = rs.getInt("round"),
                    belief = rs.getFloat("belief"),
                    stateData = rs.getBytes("state_data"),
                    isSpeaking = rs.getBoolean("speaking")
                    )
                
                if (i == (numberOfAgents - 1)) {
                    if (printResults) {
                        writer.print(
                            s"${i + 1} : [ " +
                              s"${result.belief}, " +
                              s"${result.isSpeaking}, " +
                              s"${if (rs.getString("silence_effect") == "Memory") Decoder.decode(result.stateData)(0) + ", " else ""}" +
                              s"${result.toleranceRadius}] >"
                            )
                        
                    }
                    printResults = false
                }
                
                if (printResults) {
                    writer.print(s"${i + 1} : [ ${result.belief}, ${result.isSpeaking}, " +
                                   s"${if (rs.getString("silence_effect") == "Memory") Decoder.decode(result.stateData)(0) + ", " else ""}" +
                                   s"${result.toleranceRadius}], ")
                }
                
                ids.put(result.agentId.toString, i + 1)
                i = (i + 1) % numberOfAgents
            }
            
            val neighborSQL =
                """
                  |WITH RECURSIVE latest_run AS (
                  |    SELECT id
                  |    FROM runs
                  |    ORDER BY id DESC
                  |    LIMIT 1
                  |),
                  |latest_network AS (
                  |    SELECT id as network_id
                  |    FROM networks
                  |    WHERE run_id = (SELECT id FROM latest_run)
                  |    ORDER BY final_round DESC
                  |    LIMIT 1
                  |)
                  |SELECT * FROM neighbors WHERE source IN (
                  |        SELECT id
                  |        FROM agents
                  |        WHERE network_id = (SELECT network_id FROM latest_network)
                  |    )
                  |""".stripMargin
            stmt = conn.prepareStatement(neighborSQL)
            val neighbors = stmt.executeQuery()
            neighbors.next()
            writer.print(s"\n\n< (${ids(neighbors.getString(1))} , " +
                           s"${ids(neighbors.getString(2))}) : " +
                           s"${neighbors.getFloat(3)} >")
            while (neighbors.next()) {
                writer.print(s", < (${ids(neighbors.getString(1))} , " +
                               s"${ids(neighbors.getString(2))}) : " +
                               s"${neighbors.getFloat(3)} >")
            }
            
        } catch {
            case e: Exception =>
                e.printStackTrace()
                throw e
        } finally {
            if (stmt != null) stmt.close()
            if (conn != null) conn.close()
            writer.close()
        }
    }
    
}
