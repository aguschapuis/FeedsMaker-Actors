# endpoint /feed 

En este endpoint tomamos un parametro url y otro since que es opcional

Le pasamos los mismos un actor Requester con el msg SynqRequest el cual nos devuelve un feed o tambien puede devolvernos un error internos del sistema el cual manejamos con distintos if 

# Actor Requestor  

Este actor puede recibir un solo tipo de mensaje el cual es SynqRequest que trae en sus parametro un url y un since(opcional), este se encarga de contruir la informacion del feed para luego ser devuelta a quien lo solicito (requestor)

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
