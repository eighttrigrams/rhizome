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

There is also special behaviour on `PUT /api/items/:id`  
(which is currently only for updating an Item's description). It is not necessary  
to pass the recording gate when the following is the case
- An Item with human readable id 'imports' exists, and
- The Item to be updated has an empty desription

Note that in such a case (where the recording gate is bypassed, in contrast to 
a circumstance when the gate is open), the Item gets associated with the 'imports'
context (if it not already is).
