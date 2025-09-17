#!/usr/bin/env python3
"""
Sistema de Inserción de Marcadores SCTE-35 para Streams HLS (Versión Corregida)
Diseñado para Google Dynamic Ad Insertion (DAI)
Usa 'threefive' para la correcta generación de marcadores y gestiona el estado del ad break.
"""

import os
import sys
import time
import logging
import threading
import tempfile
from pathlib import Path
from typing import Dict, List, Optional, Set
from dataclasses import dataclass, field
import json
import dataclasses

# Es crucial instalar la librería 'threefive' para la generación de marcadores SCTE-35
# pip install threefive
import threefive
import m3u8
import requests
from flask import Flask, jsonify, request, Response
from flask_cors import CORS

# Configuración de logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# --- Clases de Configuración y Datos ---

@dataclass
class StreamConfig:
    """Configuración para cada stream"""
    stream_id: str
    input_url: str
    output_path: str
    ad_duration: int  # segundos
    ad_interval: int  # segundos
    enabled: bool = True

@dataclass
class SCTE35Marker:
    """
    Representación y generación de un marcador SCTE-35 usando la librería 'threefive'.
    """
    pts: float
    duration: int
    event_id: int

    def _create_cue(self, segmentation_type_id: int, segmentation_message: str) -> threefive.Cue:
        """Crea la base de un comando SCTE-35."""
        cue = threefive.Cue()
        cue.command = threefive.TimeSignal()
        cue.command.time_specified_flag = True
        cue.command.pts_time = self.pts

        seg_desc = threefive.SegmentationDescriptor()
        seg_desc.segmentation_event_id = self.event_id
        seg_desc.segmentation_event_cancel_indicator = False
        seg_desc.program_segmentation_flag = True
        seg_desc.segmentation_duration_flag = True
        seg_desc.delivery_not_restricted_flag = False
        seg_desc.segmentation_duration = self.duration * 90000  # Convertir a 90kHz clock
        seg_desc.segmentation_type_id = segmentation_type_id
        seg_desc.segmentation_message = segmentation_message
        seg_desc.segment_num = 0
        seg_desc.segments_expected = 0

        cue.descriptors.append(seg_desc)
        return cue

    def encode_out_as_base64(self) -> str:
        """
        Codifica un marcador CUE-OUT (Provider Placement Opportunity Start).
        Este es el marcador que inicia la pausa publicitaria.
        """
        # 0x34: Provider Placement Opportunity Start
        cue = self._create_cue(0x34, "Start Ad Break")
        return cue.encode_as_base64()

    def encode_in_as_base64(self) -> str:
        """
        Codifica un marcador CUE-IN (Provider Placement Opportunity End).
        Este es el marcador que finaliza la pausa publicitaria.
        """
        # 0x35: Provider Placement Opportunity End
        cue = self._create_cue(0x35, "End Ad Break")
        return cue.encode_as_base64()


# --- Lógica Principal de Procesamiento HLS ---

class HLSProcessor:
    """
    Procesador principal de streams HLS.
    Ahora gestiona el estado para manejar correctamente las pausas publicitarias.
    """
    def __init__(self, config: StreamConfig):
        self.config = config
        self.running = False
        self.segments_processed = 0
        self._stop_event = threading.Event()

        # --- Estado del Ad Break ---
        self.in_ad_break: bool = False
        self.ad_break_event_id: Optional[int] = None
        self.ad_break_start_time: Optional[float] = None # <-- CAMBIO: Usamos un timestamp de inicio
        self.next_marker_time: float = time.time() + config.ad_interval

    def stop(self):
        """Detiene el procesamiento del stream."""
        self.running = False
        self._stop_event.set()
        logger.info(f"Señal de detención enviada a {self.config.stream_id}")

    def process_stream(self):
        """Bucle principal que procesa el stream HLS e inserta marcadores."""
        self.running = True
        logger.info(f"Iniciando procesamiento de stream: {self.config.stream_id}")

        while self.running and not self._stop_event.is_set():
            try:
                # 1. Descargar la playlist de origen
                playlist = self._download_playlist(self.config.input_url)
                if not playlist:
                    time.sleep(2) # Esperar un poco más si la descarga falla
                    continue

                # 2. Procesar la playlist para insertar/gestionar marcadores
                self._process_playlist_for_markers(playlist)

                # 3. Guardar la playlist modificada
                self._save_playlist(playlist)
                
                if not self.running:
                    break

                # 4. Descargar los nuevos segmentos
                self._process_segments(playlist)

                # 5. Limpiar segmentos antiguos
                self._cleanup_old_segments(playlist)

                # Esperar antes de la siguiente actualización
                sleep_duration = (playlist.target_duration / 2) if playlist.target_duration else 2
                time.sleep(max(1, sleep_duration))

            except Exception as e:
                logger.error(f"Error grave en el bucle de {self.config.stream_id}: {e}", exc_info=True)
                time.sleep(5)

        logger.info(f"Procesamiento de stream {self.config.stream_id} detenido.")

    def _download_playlist(self, url: str) -> Optional[m3u8.M3U8]:
        """Descarga y parsea una playlist HLS."""
        try:
            response = requests.get(url, timeout=5)
            response.raise_for_status()
            playlist = m3u8.loads(response.text, uri=url)
            for seg in playlist.segments:
                if not hasattr(seg, 'custom_tags'):
                    seg.custom_tags = []
            return playlist
        except requests.exceptions.RequestException as e:
            logger.warning(f"Error descargando playlist para {self.config.stream_id}: {e}")
            return None

    def _process_playlist_for_markers(self, playlist: m3u8.M3U8):
        """
        Lógica con estado para insertar marcadores SCTE-35.
        Decide si iniciar o finalizar un ad break basándose en tiempo real (reloj).
        """
        current_time = time.time()

        # --- Lógica de estado del Ad Break (basada en tiempo real) ---
        if self.in_ad_break:
            # Si estamos en un ad break, verificamos si ha transcurrido la duración configurada.
            time_in_break = current_time - self.ad_break_start_time
            if time_in_break >= self.config.ad_duration:
                logger.info(f"[{self.config.stream_id}] Duración de ad ({self.config.ad_duration}s) cumplida. Insertando CUE-IN.")
                self._insert_cue_in(playlist)
                # Salir del modo ad break
                self.in_ad_break = False
                self.ad_break_event_id = None
                self.ad_break_start_time = None
                self.next_marker_time = current_time + self.config.ad_interval
        else:
            # Si no estamos en un ad break, verificamos si es hora de empezar uno
            if current_time >= self.next_marker_time:
                logger.info(f"[{self.config.stream_id}] Intervalo de ad alcanzado. Insertando CUE-OUT.")
                self._insert_cue_out(playlist)
                # Entrar en modo ad break
                self.in_ad_break = True
                self.ad_break_start_time = current_time

    def _insert_cue_out(self, playlist: m3u8.M3U8):
        """Inserta un marcador CUE-OUT al inicio de la playlist."""
        if not playlist.segments:
            return

        pts = time.time() * 90000
        event_id = int(time.time())
        self.ad_break_event_id = event_id

        marker = SCTE35Marker(
            pts=pts,
            duration=self.config.ad_duration,
            event_id=event_id
        )

        scte35_tag = f"#EXT-X-SCTE35:CUE=\"{marker.encode_out_as_base64()}\""
        playlist.segments[0].custom_tags.insert(0, scte35_tag)
        logger.info(f"[{self.config.stream_id}] Marcador CUE-OUT (ID: {event_id}) insertado.")

    def _insert_cue_in(self, playlist: m3u8.M3U8):
        """Inserta un marcador CUE-IN en el segmento apropiado."""
        if not playlist.segments:
            return

        pts = time.time() * 90000
        marker = SCTE35Marker(
            pts=pts,
            duration=self.config.ad_duration,
            event_id=self.ad_break_event_id
        )

        scte35_tag = f"#EXT-X-SCTE35:CUE=\"{marker.encode_in_as_base64()}\""
        cue_in_tag = "#EXT-X-CUE-IN"

        playlist.segments[-1].custom_tags.append(scte35_tag)
        playlist.segments[-1].custom_tags.append(cue_in_tag)
        logger.info(f"[{self.config.stream_id}] Marcador CUE-IN (ID: {self.ad_break_event_id}) insertado.")

    def _save_playlist(self, playlist: m3u8.M3U8):
        """Guarda la playlist modificada en el directorio de salida, forzando EXTINF a duración entera."""
        try:
            output_dir = Path(self.config.output_path)
            output_dir.mkdir(parents=True, exist_ok=True)
            playlist_path = output_dir / "playlist.m3u8"
            # Forzar EXTINF a duración entera
            playlist_text = playlist.dumps()
            import re
            # Reemplaza cualquier EXTINF:float o EXTINF:int por EXTINF:int
            playlist_text = re.sub(r"#EXTINF:([0-9]+(?:\.[0-9]+)?),", lambda m: f"#EXTINF:{int(round(float(m.group(1))))},", playlist_text)
            playlist_path.write_text(playlist_text)
        except PermissionError:
            logger.critical(
                f"FATAL: Permiso denegado para escribir en el directorio: {self.config.output_path}. "
                f"Asegúrate de que el script tiene permisos de escritura en esa ubicación. "
                f"Deteniendo el stream {self.config.stream_id}."
            )
            self.stop()
        except Exception as e:
            logger.error(f"Error inesperado al guardar la playlist para {self.config.stream_id}: {e}")


    def _process_segments(self, playlist: m3u8.M3U8):
        """Descarga y copia segmentos que aún no existen localmente usando descarga paralela."""
        output_dir = Path(self.config.output_path)
        
        if not os.access(output_dir, os.W_OK):
            logger.error(f"Error de permisos: No se puede escribir en el directorio '{output_dir}'. "
                         f"Saltando la descarga de segmentos para {self.config.stream_id}.")
            return

        import concurrent.futures
        segments_to_download = []
        for segment in playlist.segments:
            segment_name = Path(segment.uri).name
            segment_path = output_dir / segment_name
            if not segment_path.exists():
                segments_to_download.append((segment, segment_name, segment_path))

        def download_segment(args):
            segment, segment_name, segment_path = args
            tmp_file_path = None
            try:
                response = requests.get(segment.absolute_uri, timeout=10, stream=True)
                response.raise_for_status()
                with tempfile.NamedTemporaryFile(delete=False, dir=output_dir, suffix=".ts") as tmp_file:
                    for chunk in response.iter_content(chunk_size=8192):
                        tmp_file.write(chunk)
                    tmp_file_path = tmp_file.name
                os.rename(tmp_file_path, segment_path)
                return True
            except PermissionError as e:
                logger.error(f"Error de permisos al escribir el segmento {segment_name} en {output_dir}: {e}")
                return False
            except Exception as e:
                logger.error(f"Error descargando segmento {segment.uri} para {self.config.stream_id}: {e}")
                return False
            finally:
                if tmp_file_path and os.path.exists(tmp_file_path):
                    os.remove(tmp_file_path)

        # Descargar en paralelo (máximo 4 hilos por defecto)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            results = list(executor.map(download_segment, segments_to_download))
            self.segments_processed += sum(1 for r in results if r)

    def _cleanup_old_segments(self, playlist: m3u8.M3U8):
        """Elimina los archivos de segmento .ts que ya no están en la playlist."""
        output_dir = Path(self.config.output_path)
        if not output_dir.exists():
            return

        current_segment_files = {Path(seg.uri).name for seg in playlist.segments}

        try:
            for item in output_dir.iterdir():
                if item.is_file() and item.name.endswith('.ts'):
                    if item.name not in current_segment_files:
                        logger.debug(f"Limpiando segmento antiguo: {item.name}")
                        item.unlink()
        except Exception as e:
            logger.error(f"Error durante la limpieza de segmentos antiguos: {e}")

# --- Gestor de Streams y API Flask ---

STREAMS_CONFIG_FILE = "streams.json"

def save_streams_config(configs):
    """Guarda la configuración de streams activos en un archivo JSON."""
    with open(STREAMS_CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump([dataclasses.asdict(cfg) for cfg in configs.values()], f, ensure_ascii=False, indent=2)

def load_streams_config():
    """Carga la configuración de streams activos desde un archivo JSON."""
    if not os.path.exists(STREAMS_CONFIG_FILE):
        return []
    with open(STREAMS_CONFIG_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

class StreamManager:
    """Gestor de múltiples streams."""
    def __init__(self):
        self.streams: Dict[str, HLSProcessor] = {}
        self.configs: Dict[str, StreamConfig] = {}
        self.threads: Dict[str, threading.Thread] = {}

    def add_stream(self, config: StreamConfig) -> bool:
        """Añade e inicia un nuevo stream para procesar."""
        if config.stream_id in self.streams:
            logger.warning(f"Stream {config.stream_id} ya existe.")
            return False

        processor = HLSProcessor(config)
        self.streams[config.stream_id] = processor
        self.configs[config.stream_id] = config

        thread = threading.Thread(
            target=processor.process_stream,
            name=f"Stream-{config.stream_id}",
            daemon=True
        )
        thread.start()
        self.threads[config.stream_id] = thread
        logger.info(f"Stream {config.stream_id} añadido y procesador iniciado.")
        save_streams_config(self.configs)
        return True

    def remove_stream(self, stream_id: str) -> bool:
        """Detiene y elimina un stream."""
        if stream_id in self.streams:
            logger.info(f"Iniciando eliminación del stream {stream_id}.")
            processor = self.streams[stream_id]
            thread = self.threads[stream_id]

            processor.stop()
            thread.join(timeout=10)

            if thread.is_alive():
                logger.warning(f"El hilo del stream {stream_id} no terminó a tiempo.")

            del self.streams[stream_id]
            del self.configs[stream_id]
            del self.threads[stream_id]
            logger.info(f"Stream {stream_id} eliminado exitosamente.")
            save_streams_config(self.configs)
            return True
        return False

    def get_stream_status(self, stream_id: str) -> Dict:
        """Obtiene el estado de un stream específico."""
        if stream_id not in self.streams:
            return {"error": "Stream no encontrado"}

        processor = self.streams[stream_id]
        config = self.configs[stream_id]
        thread = self.threads[stream_id]

        return {
            "stream_id": stream_id, "input_url": config.input_url,
            "output_path": config.output_path, "ad_duration": config.ad_duration,
            "ad_interval": config.ad_interval, "enabled": config.enabled,
            "running": processor.running and thread.is_alive(),
            "in_ad_break": processor.in_ad_break,
            "segments_processed": processor.segments_processed,
            "next_ad_in": round(processor.next_marker_time - time.time()) if not processor.in_ad_break else 0
        }

    def list_streams(self) -> List[Dict]:
        """Lista todos los streams y su estado."""
        return [self.get_stream_status(sid) for sid in list(self.streams.keys())]


# --- Aplicación Flask e Interfaz Web ---

app = Flask(__name__)
CORS(app)
stream_manager = StreamManager()

@app.route('/')
def index():
    """Página principal con interfaz web (reemplazando alert/confirm con un modal)."""
    return '''
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SCTE-35 HLS Inserter (Corregido)</title>
    <style>
        :root { --dark-bg: #0a0e27; --medium-bg: #112240; --light-bg: #233554; --text-color: #e0e6ed; --text-color-light: #ccd6f6; --text-color-muted: #8892b0; --accent-start: #667eea; --accent-end: #764ba2; }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--dark-bg); color: var(--text-color); line-height: 1.6; }
        .container { max-width: 1200px; margin: 0 auto; padding: 2rem; }
        header { text-align: center; margin-bottom: 3rem; }
        h1 { font-size: 2.5rem; background: linear-gradient(135deg, var(--accent-start) 0%, var(--accent-end) 100%); -webkit-background-clip: text; -webkit-text-fill-color: transparent; margin-bottom: 0.5rem; }
        .subtitle { color: var(--text-color-muted); font-size: 1.1rem; }
        .card { background: var(--medium-bg); border-radius: 10px; padding: 2rem; margin-bottom: 2rem; box-shadow: 0 10px 30px rgba(0,0,0,0.3); }
        .form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 1.5rem; margin-bottom: 1.5rem; }
        .form-group { display: flex; flex-direction: column; }
        label { color: var(--text-color-light); margin-bottom: 0.5rem; font-weight: 500; }
        input { background: var(--dark-bg); border: 2px solid var(--light-bg); color: var(--text-color); padding: 0.75rem; border-radius: 5px; font-size: 1rem; transition: all 0.3s ease; }
        input:focus { outline: none; border-color: var(--accent-start); box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1); }
        button { background: linear-gradient(135deg, var(--accent-start) 0%, var(--accent-end) 100%); color: white; border: none; padding: 0.75rem 2rem; border-radius: 5px; font-size: 1rem; font-weight: 600; cursor: pointer; transition: all 0.3s ease; text-transform: uppercase; letter-spacing: 0.5px; }
        button:hover { transform: translateY(-2px); box-shadow: 0 5px 15px rgba(102, 126, 234, 0.4); }
        .stream-card { background: var(--dark-bg); border-left: 4px solid var(--light-bg); border-radius: 8px; padding: 1.5rem; margin-bottom: 1rem; transition: all 0.3s ease; }
        .stream-card.running { border-left-color: #48bb78; }
        .stream-card.in-ad { border-left-color: #f6e05e; }
        .stream-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; flex-wrap: wrap; gap: 1rem;}
        .stream-id { font-size: 1.2rem; font-weight: 600; color: var(--text-color-light); word-break: break-all; }
        .stream-status { display: inline-block; padding: 0.25rem 0.75rem; border-radius: 20px; font-size: 0.85rem; font-weight: 600; }
        .status-running { background: rgba(72, 187, 120, 0.2); color: #48bb78; }
        .status-stopped { background: rgba(245, 101, 101, 0.2); color: #f56565; }
        .stream-details { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; color: var(--text-color-muted); font-size: 0.9rem; }
        .detail-item { display: flex; flex-direction: column; word-break: break-word;}
        .detail-label { font-weight: 600; color: var(--text-color-light); margin-bottom: 0.25rem; }
        .stream-actions { margin-top: 1rem; display: flex; gap: 1rem; flex-wrap: wrap; }
        .btn-secondary { background: transparent; border: 2px solid var(--accent-start); color: var(--accent-start); padding: 0.5rem 1rem; font-size: 0.9rem; }
        .btn-secondary:hover { background: var(--accent-start); color: white; }
        .btn-danger { background: transparent; border: 2px solid #f56565; color: #f56565; }
        .btn-danger:hover { background: #f56565; color: white; }
        .empty-state { text-align: center; padding: 3rem; color: var(--text-color-muted); }
        .modal-overlay { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.7); display: none; justify-content: center; align-items: center; z-index: 1000; }
        .modal-content { background: var(--medium-bg); padding: 2rem; border-radius: 10px; text-align: center; max-width: 400px; width: 90%; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
        .modal-content h3 { margin-bottom: 1rem; color: var(--text-color-light); }
        .modal-content p { margin-bottom: 1.5rem; color: var(--text-color); }
        .modal-actions { display: flex; justify-content: center; gap: 1rem; }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>SCTE-35 HLS Inserter</h1>
            <p class="subtitle">Versión Corregida con Lógica de Estado para Google DAI</p>
        </header>

        <div class="card add-stream-form">
            <h2 style="margin-bottom: 1.5rem; color: var(--text-color-light);">Añadir Nuevo Stream</h2>
            <form id="addStreamForm">
                <div class="form-grid">
                    <div class="form-group"><label for="streamId">ID del Stream</label><input type="text" id="streamId" required placeholder="ej: stream-001"></div>
                    <div class="form-group"><label for="inputUrl">URL de Entrada (HLS)</label><input type="url" id="inputUrl" required placeholder="https://ejemplo.com/stream.m3u8"></div>
                    <div class="form-group"><label for="outputPath">Directorio de Salida</label><input type="text" id="outputPath" required placeholder="./output/stream-001"></div>
                    <div class="form-group"><label for="adDuration">Duración del Ad (s)</label><input type="number" id="adDuration" required min="1" value="30"></div>
                    <div class="form-group"><label for="adInterval">Intervalo entre Ads (s)</label><input type="number" id="adInterval" required min="30" value="300"></div>
                </div>
                <button type="submit">Añadir Stream</button>
            </form>
        </div>

        <div class="card streams-list">
            <h2 style="margin-bottom: 1.5rem; color: var(--text-color-light);">Streams Activos</h2>
            <div id="streamsList"><div class="empty-state"><p>Cargando streams...</p></div></div>
        </div>
    </div>

    <!-- Modal para notificaciones y confirmaciones -->
    <div id="modal" class="modal-overlay">
        <div class="modal-content">
            <h3 id="modalTitle"></h3>
            <p id="modalMessage"></p>
            <div id="modalActions" class="modal-actions"></div>
        </div>
    </div>

    <script>
        class Modal {
            constructor() {
                this.overlay = document.getElementById('modal');
                this.title = document.getElementById('modalTitle');
                this.message = document.getElementById('modalMessage');
                this.actions = document.getElementById('modalActions');
            }

            _createButton(text, classes, onClick) {
                const btn = document.createElement('button');
                btn.textContent = text;
                btn.className = classes;
                btn.onclick = () => {
                    this.hide();
                    if (onClick) onClick();
                };
                return btn;
            }

            showInfo(title, message) {
                this.title.textContent = title;
                this.message.textContent = message;
                this.actions.innerHTML = '';
                this.actions.appendChild(this._createButton('OK', '', null));
                this.overlay.style.display = 'flex';
            }

            showConfirm(title, message, onConfirm) {
                this.title.textContent = title;
                this.message.textContent = message;
                this.actions.innerHTML = '';
                this.actions.appendChild(this._createButton('Cancelar', 'btn-secondary', null));
                this.actions.appendChild(this._createButton('Confirmar', 'btn-danger', onConfirm));
                this.overlay.style.display = 'flex';
            }

            hide() {
                this.overlay.style.display = 'none';
            }
        }

        const modal = new Modal();

        class UIController {
            constructor() {
                this.streamsList = document.getElementById('streamsList');
                this.addStreamForm = document.getElementById('addStreamForm');
                this.init();
            }

            init() {
                this.addStreamForm.addEventListener('submit', this.handleAddStream.bind(this));
                this.loadStreams();
                setInterval(() => this.loadStreams(), 5000);
            }

            async handleAddStream(e) {
                e.preventDefault();
                const config = {
                    stream_id: document.getElementById('streamId').value,
                    input_url: document.getElementById('inputUrl').value,
                    output_path: document.getElementById('outputPath').value,
                    ad_duration: parseInt(document.getElementById('adDuration').value),
                    ad_interval: parseInt(document.getElementById('adInterval').value)
                };

                try {
                    const response = await fetch('/api/streams', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(config)
                    });
                    const result = await response.json();
                    if (response.ok) {
                        modal.showInfo('Éxito', result.message);
                        this.addStreamForm.reset();
                        this.loadStreams();
                    } else {
                        modal.showInfo('Error', result.error || 'Ocurrió un error desconocido.');
                    }
                } catch (error) {
                    modal.showInfo('Error de Red', 'No se pudo conectar con el servidor.');
                }
            }

            async removeStream(streamId) {
                modal.showConfirm(
                    'Confirmar Eliminación',
                    `¿Estás seguro de que quieres eliminar el stream "${streamId}"? Esta acción no se puede deshacer.`,
                    async () => {
                        try {
                            const response = await fetch(`/api/streams/${streamId}`, { method: 'DELETE' });
                            const result = await response.json();
                            if(response.ok) {
                                modal.showInfo('Éxito', result.message);
                                this.loadStreams();
                            } else {
                                modal.showInfo('Error', result.error);
                            }
                        } catch (error) {
                            modal.showInfo('Error de Red', 'No se pudo conectar con el servidor.');
                        }
                    }
                );
            }

            async loadStreams() {
                try {
                    const response = await fetch('/api/streams');
                    const streams = await response.json();
                    this.renderStreams(streams);
                } catch (error) {
                    console.error('Error cargando streams:', error);
                    this.streamsList.innerHTML = '<div class="empty-state"><p>Error al conectar con el servidor.</p></div>';
                }
            }

            renderStreams(streams) {
                if (!streams || streams.length === 0) {
                    this.streamsList.innerHTML = '<div class="empty-state"><p>No hay streams activos. Añade uno usando el formulario.</p></div>';
                    return;
                }

                this.streamsList.innerHTML = streams.map(stream => `
                    <div class="stream-card ${stream.running ? 'running' : ''} ${stream.in_ad_break ? 'in-ad' : ''}">
                        <div class="stream-header">
                            <span class="stream-id">${stream.stream_id}</span>
                            <span class="stream-status ${stream.running ? 'status-running' : 'status-stopped'}">
                                ${stream.running ? 'Activo' : 'Detenido'}
                            </span>
                        </div>
                        <div class="stream-details">
                            <div class="detail-item"><span class="detail-label">URL Entrada</span><span>${stream.input_url}</span></div>
                            <div class="detail-item"><span class="detail-label">URL Salida</span><span>/output/${stream.stream_id}/playlist.m3u8</span></div>
                            <div class="detail-item"><span class="detail-label">Duración Ad</span><span>${stream.ad_duration}s</span></div>
                            <div class="detail-item"><span class="detail-label">Intervalo</span><span>${stream.ad_interval}s</span></div>
                            <div class="detail-item"><span class="detail-label">Segmentos</span><span>${stream.segments_processed}</span></div>
                            <div class="detail-item"><span class="detail-label">Estado</span><span>${stream.in_ad_break ? `En Ad Break` : `Próximo Ad en ${stream.next_ad_in}s`}</span></div>
                        </div>
                        <div class="stream-actions">
                            <button class="btn-secondary" onclick="window.open('/output/${stream.stream_id}/playlist.m3u8', '_blank')">Ver Playlist</button>
                            <button class="btn-danger" onclick="ui.removeStream('${stream.stream_id}')">Eliminar</button>
                        </div>
                    </div>
                `).join('');
            }
        }

        const ui = new UIController();
    </script>
</body>
</html>
    '''

@app.route('/api/streams', methods=['GET'])
def list_streams_api():
    return jsonify(stream_manager.list_streams())

@app.route('/api/streams', methods=['POST'])
def add_stream_api():
    try:
        data = request.json
        if not all(k in data for k in ['stream_id', 'input_url', 'output_path', 'ad_duration', 'ad_interval']):
            return jsonify({"success": False, "error": "Faltan parámetros en la solicitud."}), 400

        config = StreamConfig(
            stream_id=data['stream_id'], input_url=data['input_url'],
            output_path=data['output_path'], ad_duration=int(data['ad_duration']),
            ad_interval=int(data['ad_interval'])
        )

        success = stream_manager.add_stream(config)
        if success:
            return jsonify({"success": True, "message": f"Stream '{config.stream_id}' añadido correctamente."})
        else:
            return jsonify({"success": False, "error": f"El Stream ID '{config.stream_id}' ya existe."}), 409
    except (ValueError, TypeError) as e:
        return jsonify({"success": False, "error": f"Datos inválidos: {e}"}), 400
    except Exception as e:
        logger.error(f"Error añadiendo stream: {e}", exc_info=True)
        return jsonify({"success": False, "error": "Error interno del servidor."}), 500

@app.route('/api/streams/<stream_id>', methods=['DELETE'])
def remove_stream_api(stream_id):
    success = stream_manager.remove_stream(stream_id)
    if success:
        return jsonify({"success": True, "message": "Stream eliminado correctamente."})
    else:
        return jsonify({"success": False, "error": "Stream no encontrado."}), 404

@app.route('/output/<path:stream_id>/<path:filename>')
def serve_output_files(stream_id, filename):
    """Sirve los archivos de salida (playlist y segmentos)."""
    config = stream_manager.configs.get(stream_id)
    if not config:
        return "Stream no encontrado", 404

    file_path = Path(config.output_path) / filename
    if not file_path.exists() or not file_path.is_file():
        return "Archivo no encontrado", 404

    content_type = 'application/octet-stream'
    if filename.endswith('.m3u8'):
        content_type = 'application/vnd.apple.mpegurl'
    elif filename.endswith('.ts'):
        content_type = 'video/mp2t'

    return Response(
        file_path.read_bytes(),
        mimetype=content_type,
        headers={
            'Cache-Control': 'no-cache, no-store, must-revalidate',
            'Pragma': 'no-cache', 'Expires': '0'
        }
    )

if __name__ == "__main__":
    try:
        Path("./output").mkdir(exist_ok=True)
    except PermissionError:
        print("\n" + "="*60)
        print("ERROR DE PERMISOS: No se pudo crear el directorio './output'.")
        print("Por favor, ejecuta este script en una carpeta donde tengas permisos de escritura.")
        print("="*60 + "\n")
        sys.exit(1)

    # Restaurar streams guardados y limpiar outputs
    import shutil
    restored_streams = load_streams_config()
    for cfg in restored_streams:
        output_path = cfg.get('output_path')
        if output_path and os.path.exists(output_path):
            try:
                shutil.rmtree(output_path)
            except Exception as e:
                print(f"No se pudo borrar la carpeta de output {output_path}: {e}")
    for cfg in restored_streams:
        try:
            stream_manager.add_stream(StreamConfig(**cfg))
        except Exception as e:
            print(f"No se pudo restaurar el stream {cfg.get('stream_id')}: {e}")

    print("\n" + "="*60)
    print("SCTE-35 HLS Inserter (Versión Corregida) - Sistema Iniciado")
    print("="*60)
    print("Interfaz web disponible en: http://127.0.0.1:5000")
    print("\nPresiona Ctrl+C para detener el servidor.")
    print("="*60 + "\n")

    app.run(host='0.0.0.0', port=5000, debug=False, use_reloader=False)
