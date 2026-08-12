package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.core.ui.component.button.BitChatBrandButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.model.GroupName
import com.bitchat.android.ui.theme.BitchatFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onCreateGroup: (name: String, passcode: String?) -> Boolean,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var groupNameInput by remember { mutableStateOf("") }
    var passcodeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isPresented) {
        if (isPresented) {
            groupNameInput = ""
            passcodeInput = ""
            errorMessage = null
        }
    }

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            val animatedDismiss = LocalSheetDismiss.current

            fun submit() {
                val trimmed = groupNameInput.trim()
                if (trimmed.isEmpty()) {
                    errorMessage = "Please enter a group name."
                    return
                }
                if (!GroupName.isValid(trimmed)) {
                    errorMessage = "Group name must be between 1 and 30 characters."
                    return
                }

                val passcode = passcodeInput.takeIf { it.isNotBlank() }
                val success = onCreateGroup(trimmed, passcode)
                if (success) {
                    animatedDismiss?.invoke() ?: onDismiss()
                } else {
                    errorMessage = "Could not create group."
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                BitchatSheetTopBar(
                    onClose = onDismiss,
                    title = {
                        BitchatSheetTitle(text = "Create Private Group")
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Group Name",
                        fontFamily = BitchatFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = groupNameInput,
                        onValueChange = { input ->
                            if (input.length <= 30) {
                                groupNameInput = input
                                errorMessage = null
                            }
                        },
                        placeholder = { Text("Festival Friends", fontSize = 14.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Optional Passcode",
                        fontFamily = BitchatFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Local app access lock only",
                        fontFamily = BitchatFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = passcodeInput,
                        onValueChange = { input ->
                            passcodeInput = input
                        },
                        placeholder = { Text("Optional local passcode", fontSize = 14.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = errorMessage!!,
                            fontFamily = BitchatFontFamily,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { submit() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Create Group",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
