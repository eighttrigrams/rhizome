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

Context creation with `POST /api/contexts` bypasses the recording gate,
except when the human readable id is 'imports' (then it is refused).
Nothing may, with the gate shut, create a context with the handle 'imports'
or turn an Item with that handle into a context, since that handle is what
opens the doors above.

`PUT /api/items/:id/is-context` turns an Item into a context or back.
With the gate shut it is let through only when
- the Item is to become a context (never the other way), and
- every version of the Item (current and history) has a source which is
  not ours ('app', 'obsidian'); a blank source counts as ours, and
- the Item does not have the human readable id 'imports'

`PUT /api/items/:id` also passes the recording gate, without the 'imports'
Item and without associating anything, when the new description lowers
no line of ours: every line of ours in the current description is still
there afterwards, and none would have a lower us-vs-them caution than
now. Otherwise it is refused with 409, listing the lines that would degrade.
The 'imports' case above stays as it is and is tried first.

`PATCH /api/items/:id` changes properties other than the description
(title, short-title, tags, annotation, date, sort-idx, hide-in-global-search,
human-readable-id), one or more at a time, each with its old and its new value.
The old value is compared with what is stored, and only when every old value
matches are the new ones written, all of them or none, with check and write in
one transaction. A mismatch is refused with 409, listing each property whose
old value was wrong with what is there now. It has no door: with the gate shut
the request is logged and dropped like any other.
