// CORRECTED CODE FOR BookingsInvoiceFirebase class
// Replace the group booking section in your saveBookingInvoice method with this code:

} else {
    // Group booking - get all bookings with same groupBookingId
    val groupBookings = getGroupBookings(userPhone, booking.groupBookingId)

    var totalQuantity = 0
    var totalAmount = 0.0

                // First, get the total count of existing Flexi7 bookings for this user (before this group)
                val existingFlexi7Count = bookingsRepository.getFlexi7BookingsCount(userId)
                
                groupBookings.forEachIndexed { index, groupBooking ->
                    // Calculate individual booking amounts based on its Flexi7 status
                    val flexi7Index = if (groupBooking.isFlexi7Booking) {
                        existingFlexi7Count + index  // Add the index within this group
                    } else {
                        -1
                    }
                    
                    val individualIsFlexi7Free = if (groupBooking.isFlexi7Booking) {
                        flexi7Index < 3  // First 3 are free (indices 0, 1, 2)
                    } else {
                        false
                    }

        val individualBaseValue = roundToTwoDecimals(convenienceFee / 1.18)
        val individualDiscountAmount = roundToTwoDecimals(if (individualIsFlexi7Free) individualBaseValue else groupBooking.discountAmount)
        val individualTaxableValue = maxOf(0.0, individualBaseValue - individualDiscountAmount)
        val individualDiscountPercentage = if (individualIsFlexi7Free) {
            if (individualBaseValue > 0) (individualDiscountAmount / individualBaseValue) * 100 else 0.0
        } else {
            groupBooking.discountInPercentage
        }

        // Calculate individual GST components
        val individualCgstAmount = roundToTwoDecimals(if (individualTaxableValue > 0) individualTaxableValue * 0.09 else 0.0)
        val individualSgstAmount = roundToTwoDecimals(if (individualTaxableValue > 0) individualTaxableValue * 0.09 else 0.0)
        val individualTotalGst = individualCgstAmount + individualSgstAmount

        // Calculate individual total amount
        val individualTotalAmount = roundToTwoDecimals(individualTaxableValue + individualTotalGst)
        val finalIndividualAmount = if (individualIsFlexi7Free) 0.0 else individualTotalAmount

        val item = hashMapOf(
            "bookingId" to groupBooking.id,
            "itemDescription" to getItemDescription(groupBooking),
            "quantity" to 1,
            "baseValue" to individualBaseValue,
            "discountAmount" to individualDiscountAmount,
            "discountInPercentage" to individualDiscountPercentage,
            "taxableValue" to individualTaxableValue,
            "cgstPercentage" to formatToTwoDecimals(groupBooking.gstInPercentage / 2),
            "sgstPercentage" to formatToTwoDecimals(groupBooking.gstInPercentage / 2),
            "gstAmount" to individualTotalGst,
            "cgstAmount" to individualCgstAmount,
            "sgstAmount" to individualSgstAmount,
            "convenienceFee" to convenienceFee,
            "itemTotalAmount" to roundTotalAmount(finalIndividualAmount),
            "serviceProviderId" to groupBooking.serviceProviderId,
            "serviceProviderName" to groupBooking.serviceProviderName,
            "specialization" to groupBooking.specialization,
            "subSpecialization" to groupBooking.subSpecialization,
            "selectedTimeSlot" to groupBooking.selectedTimeSlot,
            "isFlexi7FreeBooking" to individualIsFlexi7Free
        )
        (invoiceData["items"] as MutableList<Map<String, Any>>).add(item)
        totalQuantity += 1
        totalAmount += finalIndividualAmount
    }

    invoiceData["totalQuantity"] = totalQuantity
    invoiceData["totalAmount"] = roundTotalAmount(totalAmount)
}

// Note: No additional helper method needed with the corrected approach above

/*
EXPLANATION:
1. First, get the count of existing Flexi7 bookings for the user (before this group)
2. For each booking in the group, calculate its Flexi7 index as: existingCount + indexInGroup
3. If the total index is < 3 (indices 0, 1, 2), the booking is free
4. From index 3 onwards, normal charges apply
5. Free bookings get: taxableValue=0, cgstAmount=0, sgstAmount=0, totalAmount=0
6. Paid bookings get: normal tax calculations

EXAMPLE - User has 0 existing Flexi7 bookings, making 4 new ones in a group:
- existingFlexi7Count = 0
- Booking 1: flexi7Index = 0 + 0 = 0 → Free (amounts=0)
- Booking 2: flexi7Index = 0 + 1 = 1 → Free (amounts=0)  
- Booking 3: flexi7Index = 0 + 2 = 2 → Free (amounts=0)
- Booking 4: flexi7Index = 0 + 3 = 3 → Paid (normal amounts)

EXAMPLE - User has 1 existing Flexi7 booking, making 4 new ones in a group:
- existingFlexi7Count = 1
- Booking 1: flexi7Index = 1 + 0 = 1 → Free (amounts=0)
- Booking 2: flexi7Index = 1 + 1 = 2 → Free (amounts=0)  
- Booking 3: flexi7Index = 1 + 2 = 3 → Paid (normal amounts)
- Booking 4: flexi7Index = 1 + 3 = 4 → Paid (normal amounts)
*/