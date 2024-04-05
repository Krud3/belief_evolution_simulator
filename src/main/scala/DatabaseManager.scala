import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.{Connection, PreparedStatement}

class DatabaseManager {
  private val hikariConfig = new HikariConfig()
  hikariConfig.setJdbcUrl("jdbc:postgresql://localhost:5432/promueva")
  hikariConfig.setUsername("postgres")
  hikariConfig.setPassword("postgres")

  // Set maximum number of connections in the pool.
  hikariConfig.setMaximumPoolSize(20)

  // Set minimum number of idle connections in the pool.
  hikariConfig.setMinimumIdle(5)

  // Set the maximum lifetime of a connection in the pool.
  hikariConfig.setMaxLifetime(1800000) // 30 minutes

  // Set the connection timeout: how long the client will wait for a connection from the pool.
  hikariConfig.setConnectionTimeout(30000) // 30 seconds

  // Set the idle timeout: how long a connection can remain idle before being closed.
  hikariConfig.setIdleTimeout(600000) // 10 minutes

  // Additional properties for prepared statement caching.
  hikariConfig.addDataSourceProperty("cachePrepStmts", "true")
  hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250")
  hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")

  private val dataSource = new HikariDataSource(hikariConfig)

  def getConnection: Connection = dataSource.getConnection

  def insertBatch(data: List[AgentData]): Unit = {
    val insertQuery = """
      INSERT INTO agent_states (run_id, network_id, round, agent_name, belief, confidence, opinion_climate, is_speaking)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """

    val connection = getConnection
    try {
      connection.setAutoCommit(false)
      val preparedStatement = connection.prepareStatement(insertQuery)

      data.foreach { agentData =>
        preparedStatement.setString(1, agentData.agentName) // agentData.runId
        preparedStatement.setString(2, agentData.agentName) //  agentData.networkId
        preparedStatement.setInt(3, agentData.round)
        preparedStatement.setString(4, agentData.agentName)
        preparedStatement.setDouble(5, agentData.belief)
        preparedStatement.setDouble(6, agentData.confidence)
        preparedStatement.setDouble(7, agentData.opinionClimate)
        preparedStatement.setBoolean(8, agentData.isSpeaking)
        preparedStatement.addBatch()
      }

      preparedStatement.executeBatch()
      connection.commit()
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      connection.close()
    }
  }
}
