# endpoint /feed 

En este endpoint tomamos un parametro url y otro since que es opcional

Le pasamos los mismos un actor Requester con el msg SynqRequest el cual nos devuelve un feed o tambien puede devolvernos un error internos del sistema el cual manejamos con distintos if 

# Actor Requestor  

Este actor puede recibir un solo tipo de mensaje el cual es SynqRequest que trae en sus parametro un url y un since(opcional), este se encarga de contruir la informacion del feed para luego ser devuelta a quien lo solicito (requestor)