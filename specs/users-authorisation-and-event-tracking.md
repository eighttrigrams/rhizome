# Users, Authorization & Event Tracking

Rhizome provides two APIs, one served under `/ui`, the 
other under `/api`. We will ignore the `ui` one and will  
designate the latter with API in the folling.

Rhizome is meant to run on localhost. Resources can be read without 
restriction via the API but are write gated via a gate which can only  
be opened via the UI.

The recording gate is automatically bypassed for Item creation  
with `POST /api/items` under specific circumstances.  
The following are necessary:
- An Item with human readable id 'imports' exists
- Amonst the new Item's specified context is at least 'imports'

When an additional query parameter `?scrape=true` is provided,
automatic scraping may happen. Otherwise the item is inserted plainly, as is.
When the Item has undergone scraping it gets logged under provenance 'scraper',
otherwise under 'api'.
