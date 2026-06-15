"""
Converts versions.json to XML format to be used in the Flatpak metadata.
https://github.com/AChep/keyguard-version/blob/master/versions.json
"""

import argparse
import json
import logging
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Optional
from xml.dom import minidom

# --- Configuration Constants ---
LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt="%H:%M:%S")
logger = logging.getLogger(__name__)


def convert_versions_to_xml(json_file: Path, output_file: Optional[Path] = None) -> str:
    """
    Convert versions.json to XML format.
    
    Args:
        json_file: Path to the input JSON file
        output_file: Path to the output XML file (if None, prints to stdout)
    
    Returns:
        The generated XML string
    
    Raises:
        SystemExit: If file not found, invalid JSON, or other errors occur
    """
    try:
        versions = json.loads(json_file.read_text(encoding='utf-8'))
        
        releases = ET.Element('releases')
        for version_data in versions:
            release = ET.SubElement(releases, 'release')
            release.set('version', version_data['version']['semantic'])
            release.set('date', version_data['date'])
            
            # Add URL if present
            if url_text := version_data.get('url'):
                url_elem = ET.SubElement(release, 'url')
                url_elem.set('type', 'details')
                url_elem.text = url_text
            
            # Add description if present
            if summary_text := version_data['changelog'].get('summary'):
                summary_elem = ET.SubElement(release, 'description')
                p = ET.SubElement(summary_elem, 'p')
                p.text = summary_text
        
        # Pretty print XML
        xml_str = minidom.parseString(ET.tostring(releases)).toprettyxml(indent='  ')
        
        # Remove the XML declaration and empty lines
        xml_str = '\n'.join(
            line for line in xml_str.split('\n')
            if line.strip() and not line.startswith('<?xml')
        )
        
        # Output
        if output_file:
            output_file.write_text(xml_str, encoding='utf-8')
            logger.info(f"XML written to {output_file}")
        else:
            print(xml_str)
        
        return xml_str
    
    except FileNotFoundError:
        logger.error(f"File '{json_file}' not found")
        sys.exit(1)
    except json.JSONDecodeError as e:
        logger.error(f"Invalid JSON in '{json_file}': {e}")
        sys.exit(1)
    except KeyError as e:
        logger.error(f"Missing required field in JSON data: {e}")
        sys.exit(1)
    except OSError as e:
        logger.error(f"File I/O error: {e}")
        sys.exit(1)
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        sys.exit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Convert versions.json to XML format for releases',
    )
    parser.add_argument('versions_file', type=Path, help='Path to the input versions.json file')
    parser.add_argument('--output', type=Path, help="Optional output file path; prints to stdout if omitted")
    args = parser.parse_args()
    
    convert_versions_to_xml(args.versions_file, args.output)
