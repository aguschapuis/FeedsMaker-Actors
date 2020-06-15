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
