select
  event_type,
  count() as event_count,
  sum(value) as total_value
from {{ ref('events') }}
group by event_type
