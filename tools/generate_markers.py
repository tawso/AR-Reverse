"""Генерирует и повторно распознаёт маркеры ARKotlinReserve.

Зависимость для инструмента: pip install opencv-contrib-python-headless==4.13.0.92
"""

from pathlib import Path
import cv2
import numpy as np

DICTIONARY_NAME = "DICT_4X4_50"
MARKER_IDS = (7, 23)
BLACK_SQUARE_MM = 80
PNG_BLACK_PIXELS = 800
PNG_MARGIN_PIXELS = 200
A4_WIDTH = 2480
A4_HEIGHT = 3508
A4_DPI = 300

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "markers"


def marker_image(dictionary, marker_id: int, black_pixels: int, margin: int) -> np.ndarray:
    marker = cv2.aruco.generateImageMarker(dictionary, marker_id, black_pixels)
    return cv2.copyMakeBorder(marker, margin, margin, margin, margin, cv2.BORDER_CONSTANT, value=255)


def verify(dictionary, path: Path, expected_id: int) -> None:
    image = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    corners, ids, _ = cv2.aruco.ArucoDetector(dictionary).detectMarkers(image)
    decoded = [] if ids is None else ids.flatten().tolist()
    if decoded != [expected_id] or len(corners) != 1:
        raise RuntimeError(f"{path.name}: ожидался ID {expected_id}, распознано {decoded}")


def verify_page(dictionary, path: Path) -> None:
    image = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    _, ids, _ = cv2.aruco.ArucoDetector(dictionary).detectMarkers(image)
    decoded = [] if ids is None else sorted(ids.flatten().tolist())
    if decoded != sorted(MARKER_IDS):
        raise RuntimeError(f"{path.name}: ожидались ID {MARKER_IDS}, распознано {decoded}")


def build_print_page(dictionary) -> np.ndarray:
    page = np.full((A4_HEIGHT, A4_WIDTH), 255, dtype=np.uint8)
    black_pixels = round(BLACK_SQUARE_MM / 25.4 * A4_DPI)
    margin = round(15 / 25.4 * A4_DPI)
    top_positions = (260, 1900)
    for marker_id, top in zip(MARKER_IDS, top_positions):
        image = marker_image(dictionary, marker_id, black_pixels, margin)
        left = (A4_WIDTH - image.shape[1]) // 2
        page[top:top + image.shape[0], left:left + image.shape[1]] = image
        cv2.putText(page, f"ArUco {DICTIONARY_NAME} / ID {marker_id}", (left, top - 70), cv2.FONT_HERSHEY_SIMPLEX, 1.2, 0, 3, cv2.LINE_AA)
        cv2.putText(page, f"Black square: {BLACK_SQUARE_MM} mm", (left, top - 25), cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2, cv2.LINE_AA)
    cv2.putText(page, "PRINT AT 100% SCALE. VERIFY 80 mm WITH A RULER.", (260, A4_HEIGHT - 120), cv2.FONT_HERSHEY_SIMPLEX, 1.05, 0, 3, cv2.LINE_AA)
    return page


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    for marker_id in MARKER_IDS:
        path = OUTPUT / f"aruco-4x4-50-id-{marker_id}.png"
        cv2.imwrite(str(path), marker_image(dictionary, marker_id, PNG_BLACK_PIXELS, PNG_MARGIN_PIXELS))
        verify(dictionary, path, marker_id)
    page_path = OUTPUT / "markers-print-a4-300dpi.png"
    cv2.imwrite(str(page_path), build_print_page(dictionary), [cv2.IMWRITE_PNG_COMPRESSION, 9])
    verify_page(dictionary, page_path)
    print(f"Созданы и проверены ID {MARKER_IDS} словаря {DICTIONARY_NAME}")


if __name__ == "__main__":
    main()
