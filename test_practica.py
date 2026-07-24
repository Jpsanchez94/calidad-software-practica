# test_practica.py
# Calidad de Software - Actividad pytest
# Semana 7 - Clase 1
#
# INSTRUCCIONES:
# 1. Corre: pip install pytest
# 2. Corre: pytest test_practica.py -v
# 3. Lee cada test y entiende que verifica
# 4. Escribe 2 tests propios al final del archivo

from practica_calidad import dividir, agregar_item, calcular_total, login, leer_archivo

# TESTS YA ESCRITOS (leelos y entiendalos)

def test_dividir_resultado_correcto():
    """Una division normal debe dar el resultado correcto."""
    assert dividir(10, 2) == 5.0

def test_dividir_entre_cero():
    """Dividir entre cero debe lanzar un error, no devolver un resultado."""
    import pytest
    with pytest.raises(ZeroDivisionError):
        dividir(10, 0)

def test_calcular_total_lista_normal():
    """El total de una lista de precios debe calcularse correctamente."""
    # descuento_especial = 50 hardcodeado en la funcion
    resultado = calcular_total([100, 200, 300])
    assert resultado == 550  # 600 - 50

def test_calcular_total_lista_vacia():
    """Una lista vacia debe devolver -50 (0 menos el descuento fijo)."""
    assert calcular_total([]) == -50

def test_agregar_item_devuelve_lista():
    """agregar_item debe devolver una lista."""
    resultado = agregar_item("manzana", [])
    assert isinstance(resultado, list)

def test_agregar_item_contiene_el_item():
    """El item agregado debe estar en la lista resultante."""
    resultado = agregar_item("pera", [])
    assert "pera" in resultado


# ESCRIBE TUS 2 TESTS AQUI
# Elige cualquier funcion de practica_calidad.py y escribe 2 tests.
# Recuerda: def test_nombre_descriptivo(): + assert

# Test 1:

def test_login_correcto():
    resultado = login("admin", "admin")
    assert resultado is True


# Test 2:

def test_leer_archivo(tmp_path):
    archivo = tmp_path / "prueba.txt"
    archivo.write_text("Pruebas Unitarias con pytest")

    resultado = leer_archivo(str(archivo))

    assert resultado == "Pruebas Unitarias con pytest"