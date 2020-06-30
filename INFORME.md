# endpoint /feed 

En este endpoint tomamos un parametro url y otro since que es opcional

Le pasamos los mismos un actor Requester con el msg SynqRequest el cual nos devuelve un feed o tambien puede devolvernos un error internos del sistema el cual manejamos con distintos if 

# Actor Requestor  

Este actor puede recibir un solo tipo de mensaje el cual es SynqRequest que trae en sus parametro un url y un since(opcional), este se encarga de contruir la informacion del feed para luego ser devuelta a quien lo solicito (requestor)

# Actor Coordinator
Es el actor encargado de tomar los urls mandados en un SUBSCRIBE y crear un actor hijo para cada url, estos 
actores son del tipo Requestor, tambien manejan los pedidos enviados por el endpoint feeds para los cuales
recibe por medio de un mensaje de protocolo el parametro since y se los envia a cada hijo existente (cada uno
representa una url) para que estos realicen la coneccion con el servidor de la url y retornen los items de cada
pagina segun el url del actor. Una vez recibido de cada actor hijo los items, los procesa creando una lista que
sera enviada al usuario que mando ejecuto el endpoint.

# Jerarquía

Ni bien creamos nuestro ActorSystem en el main akka crea tres actores en el sistema (root guardian, user guardian y system guardian) de los cuáles en este laboratorio trabajaremos con el actor user guardian para crear dos nuevas instancias a partir de éste: el actor requester y el actor coordinador usando system.actorOf().
El funcionamiento es el siguiente:

1- Al enviar el mensaje "AsyncRequest(url, since)" al actor requester, este se encargará de hacer una request a la url devolviendo el feed, luego se lo procesa para extraer ciertos campos, se hace un eventual filtrado según since, y se devuelve el mensaje con su feedInfo

2- Al enviar el mensaje "AsyncRequest(url, since)" al actor coordinador, este crea una nueva instancia de un actor Requester hijo con el nombre de la url, usando context.actorOf(Props[Requester], name=url)

3- Al enviar el mensaje "DateTimeStr(since)" al actor coordinador, este generará una lista con todos los ActorRef de sus hijos (usando context.children.toList) para luego enviarle a cada hijo el mensaje "AsyncRequest(<nombre del actor>, since)" haciendo la tarea mensionada en el punto 1, una vez que se hayan obtenido todos los feed de cada hijo se realizará un eventual filtrado por fechas en la lista según indique since, y poder devolver el mensaje con la lista de feeds. (Esta es la idea de lo que debería hacer, aún no lo hemos logrado)

Decidimos utilizar este esquema puesto que con el actor coordinador creamos un hijo por cada suscribe que se hace a un feed, y al querer listar la información de los feed suscritos lo hacemos delegando la tarea al actor requester.

# Futures
Un Future[A] es un tipo contenedor que representa un cálculo que finalmente dará como resultado un valor de tipo A, cuando se completa el futuro puede que sea Success (es decir, se pudo completar el cálculo) o Failure (se agotó el tiempo de espera u ocurrió una excepción).
Utilizamos Futures para evitar que el sistema se quede bloqueado esperando terminar alguna tarea o cálculo, por ejemplo enviando un mensaje a un actor con el operador ask ? para luego obtener su eventual respuesta, de esta forma la ejecución de nuestro programa ya no es síncrona.
Si este sistema estuviese implementado de manera síncrona cada request que se hace bloquearía el sistema en general hasta que haya terminado sin poder atender a las otras que van llegando. Es decir, estaríamos desperdiciando recursos al bloquearlos esperando el resultado de una request.

#¿Qué les asegura el sistema de pasaje de mensajes y cómo se diferencia con un semáforo/mutex?
 El sistema de mensajeria nos asegura la comunicacion entre los actores, lo que es necesario
 para realizar tareas en conjunto donde un actor envie datos o pedidos a un actor y espera la devolucion de 
 este actor para continuar con el procesamiento de su propia tarea definida por su comportamiento.
 Una de las diferencias con el mutex/semaforo es que no se bloquea a los usuario en la espera y no se compite
 por un recurso, tambien se tiene una estructura de comunicacion mas compleja, dado que se necestia un sistema
 de mails para comunicarse, protocolos de comunicacion, buzones de mail, colas de mensajes y otras estructuras
 necesarias para la comunicacion.

# Punto estrella 1:
Para lograr el punto estrella cuatro se necesitará crear un actor nuevo para cada usuario
es decir para el endpoint  POST /user cada llamada a este api se creara un actor cuyo nombre
del actor será el del usuario (para ello tenemos parámetro ‘username’) y este estará asociado al actor
Coordinator ya sea como hijo, donde este lo creara a partir de su contexto o por medio del almacenamiento
del actor usuario en una estructura de datos, ya sea un diccionario indexado por su nombre y cuyo valor
es elactorref del actor, o por una lista. En cuanto a la implementación para el endpoint POST /suscribe,
el actor Coordinator recibirá el pedido de suscripción y llamara al usuario identificado por el parámetro
username, le enviara por medio de un mensaje el url a suscribir, este lo recibirá y suponiendo que no lo
tiene creara un actor Requestor, cuyo nombre de usuario será la url nueva, en caso de que si exista, podrá 
consultar todos los requestor creado por el mismo y determinara a partir del nombre de cada
requestor si ya existe dicha url.
En el caso del endpoint  GET /feeds se enviara un mensaje al actor Coordinator, este mensaje
contendrá el nombre del usuario. El actor Coordinator al recibir el mensaje llamara al actor
Usuario correspondiente y le enviara un mensaje solicitando los feeds. El actor Usuario llamara
a todos sus actores Requestor (recordar que el usuario tiene uno por cada url diferente) y les 
envía un mensaje solicitando el feed de la url que representa cada uno, estos retornan los feed,
el actor usuario los almacena y luego se los envía al actor Coordinator el cual retorna la respuesta
al servidor.

  