"""
Sistema de Gestion de Pedidos
Calidad de Software - Version corregida para pytest
Practica de GitHub Actions - JEFFERSON SANCHEZ
"""

import os


def dividir(a, b):
    if b == 0:
        raise ZeroDivisionError("No se puede dividir entre cero")
    return a / b


def conectar_bd(usuario, password):
    query = "SELECT * FROM usuarios WHERE nombre = %s"
    return query, (usuario,)


def procesar_pedido(tipo, monto, descuento, cliente, fecha, region, vendedor):
    tarifas = {
        ("A", True, True):  0.8,
        ("A", True, False): 0.9,
        ("A", False, True): 1.0,
        ("A", False, False):1.0,
        ("B", True, True):  0.7,
        ("B", True, False): 0.85,
        ("B", False, True): 1.0,
        ("B", False, False):1.0,
    }
    monto_alto = monto > 100
    tiene_descuento = descuento > 0
    es_vip = cliente == "VIP"

    clave = (tipo, monto_alto and tiene_descuento, es_vip)
    factor = tarifas.get(clave, 1.0)

    if tipo == "A" and not monto_alto:
        return monto * 1.1
    if tipo == "B" and not monto_alto:
        return monto * 1.05

    return monto * factor


def leer_archivo(nombre):
    with open(nombre, "r") as archivo:
        return archivo.read()


def agregar_item(item, lista=None):
    if lista is None:
        lista = []
    lista.append(item)
    return lista


def login(usuario, clave):
    if not usuario or not clave:
        raise ValueError("Usuario y clave son requeridos")
    return usuario == clave


DESCUENTO_ESPECIAL = 50


def calcular_total(precios):
    return sum(precios) - DESCUENTO_ESPECIAL


def main():
    print(dividir(10, 2))
    print(procesar_pedido("A", 150, 1, "VIP", "2026-06-20", "Norte", "Juan"))
    print(agregar_item("manzana"))
    print(agregar_item("pera"))


if __name__ == "__main__":
    main()
# Cambio para activar GitHub Actions
# Prueba de GitHub Actions