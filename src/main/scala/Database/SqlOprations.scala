package Database
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.sql.{Connection, DriverManager, ResultSet}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import akka.http.scaladsl.server.Directives._

import scala.annotation.tailrec

case class User(id: Int, name: String, startTime: String, createdAt: String, password: String)

trait userJsonProtocol extends DefaultJsonProtocol {
  implicit val userFormat: RootJsonFormat[User] = jsonFormat5(User)
}


class SqlOperations(url: String, username: String, password: String) {

  private val currentDateTime = LocalDateTime.now()
  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  private val currentDateTimeString = currentDateTime.format(formatter)

  private def getConnection: Connection =
    DriverManager.getConnection(url, username, password)

  private def parseResultSet(resultSet: ResultSet): List[User] = {
    @tailrec
    def loop(acc: List[User]): List[User] = {
      if (resultSet.next()) {
        val id = resultSet.getInt("id")
        val name = resultSet.getString("name")
        val startTime = resultSet.getString("startTime")
        val password = resultSet.getString("password")
        val createdAt = resultSet.getString("createdAt")
        val user = User(id, name, startTime, createdAt, password)
        loop(user :: acc)
      } else {
        acc.reverse
      }
    }

    loop(List.empty)
  }
  // Implementing the CRUD Operations

  def getAllUsers: List[User] = {
    val connection = getConnection
    val statement = connection.createStatement()
    val query = "SELECT * FROM USERS"
    val users = parseResultSet(statement.executeQuery(query))
    connection.close()
    statement.close()
    users
  }

  def findUserById(userId: Int): Option[User] = {
    val connection = getConnection
    val statement = connection.createStatement()
    val query = s"SELECT * FROM USERS WHERE id = $userId"
    val resultSet = statement.executeQuery(query)
    val userOption =
      if (resultSet.next()) {
        val id = resultSet.getInt("id")
        val name = resultSet.getString("name")
        val startTime = resultSet.getString("startTime")
        val createdAt = resultSet.getString("createdAt")
        val password = resultSet.getString("password")
        Some(User(id, name, startTime, createdAt, password))
      } else {
        None
      }
    userOption
  }

  def updateUserById(userId: Int, updatedUser: User): Option[User] = {
    val connection = getConnection
    val query = s"UPDATE USERS SET name = '${updatedUser.name}', startTime = '${updatedUser.startTime}', createdAt ='$currentDateTimeString' WHERE id = $userId"
    val statement = connection.createStatement()
    val rowsUpdated = statement.executeUpdate(query)
    if (rowsUpdated == 1) {
      println(s"Executed Query: $query")
      println(s"Rows Updated: $rowsUpdated")
      statement.close()
      connection.close()
      Some(updatedUser)
    }
    else {
      None
    }
  }


  def addUser(newUser: User): Option[User] = {
    val connection = getConnection
    val query = "INSERT INTO USERS (id, name, startTime,createdAt, password) VALUES (?, ?, ?, ?, ?)"
    val preparedStatement = connection.prepareStatement(query)
    preparedStatement.setInt(1, newUser.id)
    preparedStatement.setString(2, newUser.name)
    preparedStatement.setString(3, newUser.startTime)
    preparedStatement.setString(4, newUser.createdAt)
    preparedStatement.setString(5, newUser.password)
    val checkQuery = s"SELECT name FROM USERS WHERE id=${newUser.id}"
    if (connection.createStatement().executeQuery(checkQuery).next()) {
      preparedStatement.close()
      connection.close()
      None
    } else {
      preparedStatement.executeUpdate()
      preparedStatement.close()
      connection.close()
      Some(newUser)
    }
  }

  def authenticateUser(user: User): Option[User] = {
    val connection = getConnection
    val statement = connection.createStatement()
    val query = s"SELECT * FROM USERS WHERE id= '${user.id}' AND name='${user.name}' AND password='${user.password}'"
    val result = statement.executeQuery(query)
    if (result.next()) {
      val id = result.getInt("id")
      val name = result.getString("name")
      val startTime = result.getString("startTime")
      val createdAt = result.getString("createdAt")
      val password = result.getString("password")
      Some(User(id, name, startTime, createdAt, password))
    } else {
      None
    }
  }

  def validatePassword(password: String): Boolean = {
    val hasDigit = """\d""".r.findFirstMatchIn(password).isDefined
    val hasChar = """[a-zA-Z]""".r.findFirstMatchIn(password).isDefined
    val hasSpecialChar = """[!@#$%^&*(){}\[\]|;:'"<>,.?/~`_-]""".r.findFirstMatchIn(password).isDefined
    val hasValidLength = password.length > 8
    hasDigit && hasChar && hasSpecialChar && hasValidLength
  }

}

object SqlOperations extends App with SprayJsonSupport with userJsonProtocol {
    private val url = "jdbc:mysql://localhost:3306/HttpAkka"
    private val username = "root"
    private val password = "___13__"
    private val currentDateTime = LocalDateTime.now()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val currentDateTimeString = currentDateTime.format(formatter)
    private val userOperations = new SqlOperations(url, username, password)
    private val requestHandler = path("api")  {
      post{
        entity(as[User]){
          user =>
            if (userOperations.validatePassword(user.password)) {
              userOperations.addUser(user) match {
                case Some(_) => complete(StatusCodes.OK)
                case None => complete(StatusCodes.BadRequest,"User id is already present in the database")
              }
            } else {
              complete(StatusCodes.NotModified, "The password should contain a digit,character, special character and length should be greater than 8")
            }

        }
      }~
      patch {
        entity(as[User]) { updatedUser =>
          parameter('id.as[Int]) { userId =>
            val user = User(userId, updatedUser.name, updatedUser.startTime,currentDateTimeString,updatedUser.password)
            if (userOperations.validatePassword(user.password)) {
              userOperations.updateUserById(userId, user) match {
                case Some(_) => complete(StatusCodes.OK)
                case None => complete(StatusCodes.NotFound, "The UserID is not found") // proper status code
              }
            } else {
              complete(StatusCodes.NotModified, "The password should contain a digit,character, special character and length should be greater than 8")
            }

          }
        }
      }~
      get {
        (path(IntNumber) | parameter('id.as[Int])) { id =>
          //print the user with id as id
          userOperations.findUserById(id) match {
            case Some(user) => complete(user)
            case None => complete(StatusCodes.NotFound, "The User ID is not found")
          }
        } ~
        entity(as[User]){
//          print all the users in my database1
          user =>
            userOperations.authenticateUser(user) match {
              case Some(_) => complete(userOperations.getAllUsers)
              case None => complete(StatusCodes.Unauthorized)
            }
        }
    }
    }
    implicit val system: ActorSystem = ActorSystem("SqlOperations")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    import system.dispatcher
    Http().bindAndHandle(requestHandler,"localhost",9090)
}


