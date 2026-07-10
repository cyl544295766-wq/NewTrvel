import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useCurrentUser, useLogout } from '../../auth';
import { TripList } from '../components/TripList';
import { useTrips } from '../hooks/useTrips';

export function TripsPage() {
  const currentUser = useCurrentUser();
  const logout = useLogout();
  const trips = useTrips();
  const [filter, setFilter] = useState<'all' | 'favorite'>('all');
  const allTrips = trips.data?.trips ?? [];
  const visibleTrips = filter === 'favorite' ? allTrips.filter((trip) => trip.isFavorite) : allTrips;

  return (
    <main className="app-page">
      <header className="top-bar">
        <div>
          <p className="eyebrow">Travel OS</p>
          <h1>My Trips</h1>
        </div>
        <div className="top-actions">
          <span>{currentUser.data?.user.displayName}</span>
          <button className="secondary-button" onClick={() => logout.mutate()} type="button">
            Logout
          </button>
        </div>
      </header>
      <section className="content-panel">
        <div className="panel-heading">
          <div>
            <h2>Trips</h2>
            <p>Trips you created or joined.</p>
          </div>
          <Link className="button-link" to="/trips/new">
            New Trip
          </Link>
        </div>
        <div className="filter-tabs" role="tablist" aria-label="Trip filters">
          <button
            aria-selected={filter === 'all'}
            className={filter === 'all' ? 'tab-button active-tab' : 'tab-button'}
            onClick={() => setFilter('all')}
            role="tab"
            type="button"
          >
            All
          </button>
          <button
            aria-selected={filter === 'favorite'}
            className={filter === 'favorite' ? 'tab-button active-tab' : 'tab-button'}
            onClick={() => setFilter('favorite')}
            role="tab"
            type="button"
          >
            My Favorites
          </button>
        </div>
        {trips.isLoading ? <p>Loading...</p> : <TripList trips={visibleTrips} />}
      </section>
    </main>
  );
}
