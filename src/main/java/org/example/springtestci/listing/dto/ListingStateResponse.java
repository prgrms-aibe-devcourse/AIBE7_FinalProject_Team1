package org.example.springtestci.listing.dto;

import org.example.springtestci.listing.entity.ListingStatus;

public record ListingStateResponse(long listingId, long contentVersion, ListingStatus status) {}
