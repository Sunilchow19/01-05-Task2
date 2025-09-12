// CORRECTED CODE FOR BookingsInvoiceFirebase class
// Replace the group booking section in your saveBookingInvoice method with this code:

} else {
    // Group booking - get all bookings with same groupBookingId
    val groupBookings = getGroupBookings(userPhone, booking.groupBookingId)

    var totalQuantity = 0
    var totalAmount = 0.0

    groupBookings.forEachIndexed { index, groupBooking ->
        // Calculate individual booking amounts based on its Flexi7 status
        val flexi7Index = if (groupBooking.isFlexi7Booking) {
            getFlexi7BookingIndexForUser(userId, groupBooking.timeStamp)
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

// ADD this helper method to your BookingsInvoiceFirebase class:
private suspend fun getFlexi7BookingIndexForUser(userId: String, currentBookingTimestamp: Long): Int {
    return try {
        val snapshot = db.collection("bookings").document(userId)
            .collection("bookings")
            .whereEqualTo("isFlexi7Booking", true)
            .whereLessThan("timeStamp", currentBookingTimestamp)
            .get()
            .await()
        snapshot.size()
    } catch (e: Exception) {
        Log.e(TAG, "Error counting previous Flexi7 bookings", e)
        0
    }
}

/*
EXPLANATION:
1. For each booking in the group, we now calculate its individual Flexi7 status
2. We check how many Flexi7 bookings the user had BEFORE this specific booking (using timestamp)
3. If it's the user's 1st, 2nd, or 3rd Flexi7 booking (index 0, 1, 2), it's free
4. From 4th booking onwards (index 3+), normal charges apply
5. Free bookings get: taxableValue=0, cgstAmount=0, sgstAmount=0, totalAmount=0
6. Paid bookings get: normal tax calculations

EXAMPLE with 4 Flexi7 bookings in a group:
- Booking 1: flexi7Index=0 → Free (amounts=0)
- Booking 2: flexi7Index=1 → Free (amounts=0)  
- Booking 3: flexi7Index=2 → Free (amounts=0)
- Booking 4: flexi7Index=3 → Paid (normal amounts)
*/