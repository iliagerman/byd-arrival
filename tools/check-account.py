"""Read-only account test. Never issues vehicle control commands or prints account data."""
import asyncio
import logging
import os
from pybyd import BydClient, BydConfig

logging.disable(logging.CRITICAL)

async def main():
    config = BydConfig(username=os.environ['USERNAME'], password=os.environ['PASSWORD'],
                       country_code=os.environ['COUNTRY'], time_zone=os.environ['TIME_ZONE'], mqtt_enabled=False)
    try:
        async with BydClient(config) as client:
            vehicles = await client.get_vehicles()
            print(f'Authenticated; vehicle count: {len(vehicles)}. No vehicle commands sent.')
    except Exception as error:
        code = getattr(error, 'code', None)
        # BYD error descriptions can contain identifiers, so report only type and numeric code.
        safe_code = str(code) if str(code).isdigit() else 'unavailable'
        print(f'Account check failed: {type(error).__name__}, code={safe_code}. No retry.')
        raise SystemExit(1) from None

asyncio.run(main())
