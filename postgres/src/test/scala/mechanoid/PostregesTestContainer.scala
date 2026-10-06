package mechanoid
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import saferis.SaferisError
import saferis.SqlSession
import saferis.postgres.jdbc.PostgresJdbc
import mechanoid.persistence.postgres.PostgresSchema
import zio.*

import javax.sql.DataSource

final case class ContainerConfig(
    imageName: String = s"${PostgreSQLContainer.IMAGE}:latest"
)
object ContainerConfig:
  val default = ZLayer.succeed(ContainerConfig())

final case class PostgresTestContainer(
    config: ContainerConfig
):
  val postgres: PostgreSQLContainer =
    new PostgreSQLContainer(config.imageName)
      .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")

  def start: PostgresTestContainer =
    postgres.start()
    this

  val stop: UIO[Unit] =
    ZIO.succeed:
      postgres.stop()
end PostgresTestContainer

object PostgresTestContainer:
  val base  = ZLayer.derive[PostgresTestContainer]
  val layer = base >>> ZLayer.scoped:
    ZIO.acquireRelease(ZIO.service[PostgresTestContainer].map(_.start))(_.stop)
  val default = ContainerConfig.default >>> layer

  final case class DataSourceProvider(container: PostgresTestContainer):
    import container.postgres

    def dataSource: DataSource =
      val ds = PGSimpleDataSource()
      ds.setURL(postgres.getJdbcUrl())
      ds.setUser(postgres.getUsername())
      ds.setPassword(postgres.getPassword())
      ds
  end DataSourceProvider

  object DataSourceProvider:
    val datasource: URLayer[PostgresTestContainer, DataSource] =
      ZLayer.derive[DataSourceProvider].map(env => ZEnvironment(env.get.dataSource))

    val default: ZLayer[Any, Nothing, DataSource] =
      PostgresTestContainer.default >>> datasource

    /** Session layer with schema initialization using PostgresSchema.initialize. */
    val transactor: ZLayer[Any, SaferisError, SqlSession] =
      default >>> PostgresJdbc.layer() >>> ZLayer.fromZIO {
        PostgresSchema.initialize.flatMap { result =>
          ZIO.logInfo(s"PostgresSchema initialized: $result")
        } *> ZIO.service[SqlSession]
      }
  end DataSourceProvider
end PostgresTestContainer
