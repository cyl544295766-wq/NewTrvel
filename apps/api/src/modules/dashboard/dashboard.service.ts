import { Injectable } from '@nestjs/common';
import { Photo, TravelDocument, Trip, TripExpense } from '@prisma/client';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { PackingListsRepository } from '../packing-lists/packing-lists.repository';
import { createPhotoThumbnail } from '../photos/photo-thumbnail';
import { PhotosRepository } from '../photos/photos.repository';
import { TripExpensesRepository } from '../trip-expenses/trip-expenses.repository';
import { TravelDocumentsRepository } from '../travel-documents/travel-documents.repository';
import { TripsRepository } from '../trips/trips.repository';

type DashboardTrip = {
  id: string;
  title: string;
  destination: string | null;
  startDate: Date | null;
  endDate: Date | null;
  status: string;
  isFavorite: boolean;
  updatedAt: Date;
};

type DashboardExpense = {
  id: string;
  tripId: string;
  tripTitle: string;
  title: string;
  amount: string;
  currency: string;
  category: string;
  spentAt: Date;
  createdAt: Date;
  payerDisplayName: string;
};

type DashboardPhoto = {
  id: string;
  tripId: string;
  tripTitle: string;
  thumbnailUrl: string;
  alt: string;
};

type DashboardDocument = {
  id: string;
  tripId: string;
  tripTitle: string;
  type: string;
  title: string;
  expiredAt: Date;
};

@Injectable()
export class DashboardService {
  constructor(
    private readonly tripsRepository: TripsRepository,
    private readonly tripExpensesRepository: TripExpensesRepository,
    private readonly packingListsRepository: PackingListsRepository,
    private readonly photosRepository: PhotosRepository,
    private readonly travelDocumentsRepository: TravelDocumentsRepository,
  ) {}

  async findOne(currentUser: AuthenticatedUser) {
    const today = this.startOfToday();
    const reminderWindowEnd = this.endOfReminderWindow(today);
    const [memberships, recentTrips, upcomingTrips] = await Promise.all([
      this.tripsRepository.findDashboardTripsForUser(currentUser.id),
      this.tripsRepository.findRecentTripsForUser(currentUser.id, 3),
      this.tripsRepository.findUpcomingTripsForUser(currentUser.id, today, 3),
    ]);
    const tripIds = memberships.map((membership) => membership.trip.id);
    const pendingPackingItemCountPromise =
      tripIds.length === 0
        ? Promise.resolve(0)
        : this.packingListsRepository.countPendingItemsForTrips(tripIds);
    const [expenses, recentExpenses, recentPhotos, upcomingDocuments] =
      tripIds.length === 0
        ? [[], [], [], []]
        : await Promise.all([
            this.tripExpensesRepository.findExpensesForTrips(tripIds),
            this.tripExpensesRepository.findRecentExpensesForTrips(tripIds, 5),
            this.photosRepository.findRecentPhotosForTrips(tripIds, 6),
            this.travelDocumentsRepository.findUpcomingDocumentsForTrips(
              tripIds,
              today,
              reminderWindowEnd,
            ),
          ]);
    const pendingPackingItemCount = await pendingPackingItemCountPromise;
    const dashboardPhotos = await Promise.all(
      recentPhotos.map((photo) => this.toDashboardPhoto(photo)),
    );

    return {
      user: {
        id: currentUser.id,
        username: currentUser.username,
      },
      stats: {
        tripCount: memberships.length,
        totalExpenseAmount: this.sumExpenses(expenses),
        totalDays: this.sumTripDays(memberships.map((membership) => membership.trip)),
        pendingPackingItemCount,
      },
      recentTrips: recentTrips.map((membership) => this.toDashboardTrip(membership.trip)),
      upcomingTrips: upcomingTrips.map((membership) => this.toDashboardTrip(membership.trip)),
      recentExpenses: recentExpenses.map((expense) => this.toDashboardExpense(expense)),
      recentPhotos: dashboardPhotos,
      upcomingDocuments: upcomingDocuments.map((document) => this.toDashboardDocument(document)),
    };
  }

  private toDashboardTrip(trip: Trip): DashboardTrip {
    return {
      id: trip.id,
      title: trip.title,
      destination: trip.destination,
      startDate: trip.startDate,
      endDate: trip.endDate,
      status: trip.status,
      isFavorite: trip.isFavorite,
      updatedAt: trip.updatedAt,
    };
  }

  private toDashboardExpense(
    expense: TripExpense & { payer: { displayName: string }; trip: { title: string } },
  ): DashboardExpense {
    return {
      id: expense.id,
      tripId: expense.tripId,
      tripTitle: expense.trip.title,
      title: expense.title,
      amount: expense.amount.toFixed(2),
      currency: expense.currency,
      category: expense.category,
      spentAt: expense.spentAt,
      createdAt: expense.createdAt,
      payerDisplayName: expense.payer.displayName,
    };
  }

  private async toDashboardPhoto(
    photo: Photo & { trip: { title: string } },
  ): Promise<DashboardPhoto> {
    return {
      id: photo.id,
      tripId: photo.tripId,
      tripTitle: photo.trip.title,
      thumbnailUrl: await createPhotoThumbnail(photo.url),
      alt: photo.caption ?? photo.trip.title,
    };
  }

  private toDashboardDocument(
    document: TravelDocument & { trip: { title: string } },
  ): DashboardDocument {
    return {
      id: document.id,
      tripId: document.tripId,
      tripTitle: document.trip.title,
      type: document.type,
      title: document.title,
      expiredAt: document.expiredAt!,
    };
  }

  private sumExpenses(expenses: TripExpense[]) {
    const cents = expenses.reduce(
      (total, expense) => total + Math.round(Number(expense.amount) * 100),
      0,
    );
    return (cents / 100).toFixed(2);
  }

  private sumTripDays(trips: Trip[]) {
    return trips.reduce((total, trip) => {
      if (!trip.startDate || !trip.endDate) {
        return total;
      }

      const start = this.toUtcDate(trip.startDate);
      const end = this.toUtcDate(trip.endDate);
      const diff = Math.floor((end.getTime() - start.getTime()) / 86_400_000);
      return total + Math.max(diff, 0);
    }, 0);
  }

  private startOfToday() {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    return today;
  }

  private endOfReminderWindow(today: Date) {
    const end = new Date(today);
    end.setDate(end.getDate() + 30);
    end.setHours(23, 59, 59, 999);
    return end;
  }

  private toUtcDate(value: Date) {
    return new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth(), value.getUTCDate()));
  }
}
